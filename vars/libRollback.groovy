// Rollback (PIPELINE_SCOPE=rollback), emergency rollback gate sau promote, rollout promote/abort + wait.
// Rollback dùng find-prev-tag.sh (auto patch-1) hoặc hub-find-tag-by-semver.sh (ROLLBACK_VERSION cụ thể).

def normalizeRollbackVersion(String raw) {
  if (!raw?.trim()) return ''
  def v = raw.trim()
  // Tự thêm 'v' nếu thiếu: '1.0.2' → 'v1.0.2'
  if (!v.startsWith('v')) { v = 'v' + v }
  // Validate semver 3 phần
  if (!(v ==~ /^v[0-9]+\.[0-9]+\.[0-9]+$/)) {
    error("ROLLBACK_VERSION không hợp lệ: '${raw}'. Dùng dạng v1.0.2 hoặc 1.0.2")
  }
  return v
}

def runRollbackSteps() {
  def envFile = "env/${params.TARGET_ENV}.yaml"
  def targetSvc = params.ROLLBACK_SERVICE?.trim()?.toLowerCase()
  def rollbackVersion = normalizeRollbackVersion(params.ROLLBACK_VERSION ?: '')

  // Validate: ROLLBACK_VERSION chỉ dùng được với 1 service cụ thể
  if (rollbackVersion && !targetSvc) {
    error("ROLLBACK_VERSION='${rollbackVersion}' yêu cầu ROLLBACK_SERVICE phải chỉ định đúng 1 service (không để trống). " +
          "Không thể rollback tất cả services về cùng 1 version vì mỗi service có version history riêng.")
  }

  def svcs = []
  if (targetSvc) {
    svcs = [targetSvc]
  } else {
    def all = sh(
      script: "bash scripts/ci/list-env-app-services.sh '${envFile}'",
      returnStdout: true
    ).trim()
    svcs = all.tokenize().findAll { it && it != 'client' }
  }

  if (svcs.isEmpty()) {
    error('Không có service nào để rollback.')
  }

  echo "Rollback targets (${envFile}): ${svcs.join(', ')}"
  if (rollbackVersion) {
    echo "Rollback mode: chỉ định version=${rollbackVersion} (tìm tag trên Hub)"
  } else {
    echo "Rollback mode: tự động patch-1 từ tag hiện tại (tìm tag trên Hub)"
  }
  env.ROLLBACK_ALREADY_HANDLED = 'true'

  // Map service → expected tag mới (sau rollback) để đợi Argo sync
  def expectedTags = [:]

  withCredentials([usernamePassword(
    credentialsId: 'github-go-micro-pat',
    usernameVariable: 'GH_USER',
    passwordVariable: 'GH_TOKEN'
  )]) {
    svcs.each { svc ->
      def currentTag = sh(
        script: "bash scripts/ci/read-env-tag.sh '${envFile}' '${svc}'",
        returnStdout: true
      ).trim()
      echo "${svc} current tag (Git/env): ${currentTag}"

      def prevTag = ''
      if (rollbackVersion) {
        // Rollback về version chỉ định: tìm tag "<svc-prefix>-<version>[-sha]" trên Hub
        // Parse prefix từ currentTag (vd: "payment-service-v" từ "payment-service-v1.0.4-abc")
        def svcPrefix = ''
        if (currentTag =~ /^(.*-v)[0-9]+\.[0-9]+\.[0-9]+/) {
          svcPrefix = (currentTag =~ /^(.*-v)[0-9]+\.[0-9]+\.[0-9]+/)[0][1]
        } else {
          error("${svc}: không parse được prefix từ tag '${currentTag}'")
        }
        // svcPrefix tận cùng bằng '-v' (vd: "payment-service-v"); rollbackVersion bắt đầu bằng 'v' (vd: "v1.0.2").
        // Bỏ 'v' đầu rollbackVersion để không double: "payment-service-v" + "1.0.2" = "payment-service-v1.0.2"
        def targetSemver = "${svcPrefix}${rollbackVersion.replaceFirst('^v', '')}"
        echo "${svc}: tìm tag '${targetSemver}[-sha]' trên Hub..."
        prevTag = sh(
          script: "bash scripts/ci/hub-find-tag-by-semver.sh '${targetSemver}'",
          returnStdout: true
        ).trim()
        if (!prevTag) {
          error("${svc}: tag '${targetSemver}' (hoặc '${targetSemver}-<sha>') không tìm thấy trên Docker Hub.")
        }
      } else {
        // Rollback tự động: patch-1, tìm tag cũ kèm SHA gốc từ Hub
        prevTag = sh(
          script: "bash scripts/ci/find-prev-tag.sh '${currentTag}'",
          returnStdout: true
        ).trim()
      }

      echo "${svc}: ${currentTag} → ${prevTag}"

      sh "bash scripts/ci/write-service-tag.sh '${envFile}' '${svc}' '${prevTag}'"
      echo "${svc}: yaml → ${prevTag}"
      expectedTags[svc] = prevTag
    }

    if (params.PUSH_GIT != true && params.PUSH_GIT != 'true') {
      echo 'PUSH_GIT=false — yaml đã sửa trên workspace; push Git thủ công hoặc bật PUSH_GIT.'
      return
    }

    sh """
      set -e
      git config user.email 'jenkins@go-micro.local'
      git config user.name 'jenkins-ci'
      git add '${envFile}'
      if git diff --cached --quiet; then
        echo 'Nothing to rollback (yaml không đổi).'
        exit 0
      fi
      git commit -m 'ci: rollback tags ${envFile} [${svcs.join(',')}] [skip ci] #${env.BUILD_NUMBER}'
      git push "https://x-access-token:\${GH_TOKEN}@github.com/minhtri1612/go-micro.git" "HEAD:${params.GIT_BRANCH}"
    """
  }

  echo 'Rollback Git xong — ArgoCD sync tag cũ (patch-1 so với tag hiện tại trong env/).'

  // Đợi Argo CD sync rollout sang tag mới + enter Paused (canary pause: {})
  // Chỉ set RESOLVED_ROLLOUT_SERVICE khi rollback đúng 1 service → Rollout Decision Gate có target.
  if (svcs.size() == 1) {
    def svc = svcs[0]
    def expectedTag = expectedTags[svc]
    env.RESOLVED_ROLLOUT_SERVICE = svc
    echo "Đợi ArgoCD sync rollout '${svc}' → image tag '${expectedTag}' (timeout 300s)..."
    waitForRolloutImageTag(params.DEV_KUBE_CONTEXT, params.ROLLOUT_NAMESPACE, svc, expectedTag, 300)
  } else {
    echo "Rollback ${svcs.size()} services — bỏ qua Rollout Decision Gate (không có target duy nhất). Promote tay từng service nếu cần."
  }
}

/** Poll kubectl rollout cho đến khi image (tag suffix) match expectedTag. Fail soft sau timeout. */
def waitForRolloutImageTag(String kubeContext, String namespace, String service, String expectedTag, int timeoutSec) {
  def status = sh(
    returnStatus: true,
    script: """
      set +e
      end=\$(( \$(date +%s) + ${timeoutSec} ))
      while [ \$(date +%s) -lt \$end ]; do
        img=\$(kubectl --context ${kubeContext} -n ${namespace} get rollout ${service} \\
          -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo '')
        case "\$img" in
          *:${expectedTag})
            echo "Rollout ${service} image = \$img (matched ${expectedTag})"
            exit 0
            ;;
        esac
        echo "  ... rollout image=\$img, chờ ${expectedTag}..."
        sleep 10
      done
      echo "[WARN] timeout ${timeoutSec}s — rollout chưa sync sang ${expectedTag}; vẫn cho gate hiển thị (bấm Promote sau khi Argo sync)."
      exit 0
    """
  )
  return status == 0
}

def rollbackGitTags(String envFile) {
  if (!env.ENV_FILE_SNAPSHOT?.trim()) {
    echo "Không có ENV_FILE_SNAPSHOT — bỏ qua rollback Git tags."
    return
  }
  echo "Reverting ${envFile} về snapshot trước build..."
  writeFile file: envFile, text: env.ENV_FILE_SNAPSHOT
  withCredentials([usernamePassword(
    credentialsId: 'github-go-micro-pat',
    usernameVariable: 'GH_USER',
    passwordVariable: 'GH_TOKEN'
  )]) {
    sh """
      set -e
      git config user.email 'jenkins@go-micro.local'
      git config user.name 'jenkins-ci'
      git add '${envFile}'
      if git diff --cached --quiet; then
        echo 'Không có gì để rollback (env/ chưa thay đổi so với snapshot).'
        exit 0
      fi
      git commit -m 'ci: rollback tags ${envFile} [skip ci] #${env.BUILD_NUMBER}'
      git push "https://x-access-token:\${GH_TOKEN}@github.com/minhtri1612/go-micro.git" "HEAD:${params.GIT_BRANCH}"
    """
  }
  echo 'Git rollback xong — ArgoCD sẽ sync lại image tag cũ.'
}

def runEmergencyRollbackGateSteps() {
  echo "Rollout [${env.RESOLVED_ROLLOUT_SERVICE}] đã Healthy. Quan sát service; Emergency Rollback nếu cần (timeout 15 phút → giữ nguyên)."
  def rawDecision = null
  def timedOut = false
  try {
    timeout(time: 15, unit: 'MINUTES') {
      rawDecision = input(
        id: "emergency-rollback-${env.BUILD_NUMBER}",
        message: "Service [${env.RESOLVED_ROLLOUT_SERVICE}] đang chạy sau promote. Xác nhận ổn hoặc Emergency Rollback?",
        ok: 'Xác nhận',
        parameters: [
          choice(
            name: 'EMERGENCY_ACTION',
            choices: ['Service OK — keep', 'Emergency Rollback'],
            description: 'Emergency Rollback = revert tag Git + abort rollout → ArgoCD sync image cũ'
          )
        ]
      )
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException ignored) {
    echo 'Timeout 15 phút không phản hồi → mặc định giữ nguyên (Service OK).'
    timedOut = true
  }

  if (timedOut) {
    return
  }

  def action = (rawDecision instanceof Map)
    ? rawDecision['EMERGENCY_ACTION']?.toString()
    : rawDecision?.toString()

  if (action == 'Emergency Rollback') {
    echo '=== EMERGENCY ROLLBACK ==='
    env.EMERGENCY_ROLLBACK_DONE = 'true'
    rollbackGitTags("env/${params.TARGET_ENV}.yaml")
    runRolloutAction(params.DEV_KUBE_CONTEXT, params.ROLLOUT_NAMESPACE, env.RESOLVED_ROLLOUT_SERVICE, 'abort')
    error('Emergency rollback: tag Git đã revert, rollout đã abort. ArgoCD sẽ sync image cũ.')
  }
  echo 'Service stable confirmed — pipeline complete.'
}

def shouldAutoAbortRolloutOnFailure() {
  if (env.EMERGENCY_ROLLBACK_DONE == 'true' || env.ROLLBACK_ALREADY_HANDLED == 'true') {
    return false
  }
  if (!params.AUTO_ABORT) {
    return false
  }
  if (!env.RESOLVED_ROLLOUT_SERVICE?.trim()) {
    return false
  }
  // auto + Parallel: một nhánh fail (vd. dependency) không abort cả rollout — dùng gate Promote/Rollback.
  return params.PIPELINE_SCOPE in ['full', 'build-and-full']
}

def runRolloutAction(String kubeContext, String namespace, String service, String action) {
  if (!(action in ['promote', 'abort'])) {
    error("Unsupported rollout action: ${action}")
  }
  sh """
    set -e
    if kubectl argo rollouts --context ${kubeContext} version >/dev/null 2>&1; then
      kubectl argo rollouts --context ${kubeContext} -n ${namespace} ${action} ${service}
    else
      echo "kubectl-argo-rollouts not found, fallback to kubectl patch for action=${action}"
      if [ "${action}" = "promote" ]; then
        kubectl --context ${kubeContext} -n ${namespace} patch rollout ${service} --type merge -p '{"spec":{"paused":false}}'
      else
        kubectl --context ${kubeContext} -n ${namespace} patch rollout ${service} --type merge -p '{"spec":{"paused":true}}'
      fi
    fi
    kubectl --context ${kubeContext} -n ${namespace} get rollout ${service}
  """
}

def waitRolloutComplete(String kubeContext, String namespace, String service) {
  String waitRaw = (params.ROLLOUT_WAIT_TIMEOUT ?: '45m').trim()
  int waitSec = parseTimeoutSeconds(waitRaw)
  // Dùng withEnv + sh ''' để Groovy không parse ${…} trong chuỗi (lỗi CPS với --timeout='${waitRaw}').
  withEnv([
    'WRC_CTX=' + kubeContext,
    'WRC_NS=' + namespace,
    'WRC_SVC=' + service,
    'WRC_DEADLINE_SEC=' + waitSec.toString(),
  ]) {
    sh '''#!/bin/bash
set -e
echo "Chờ rollout ${WRC_SVC} tới Healthy sau promote (poll mỗi 5s, tối đa ~${WRC_DEADLINE_SEC}s)..."
if ! kubectl argo rollouts --context "${WRC_CTX}" version >/dev/null 2>&1; then
  echo "WARN: không có kubectl-argo-rollouts; dùng kubectl get rollout"
fi
deadline=$(( $(date +%s) + ${WRC_DEADLINE_SEC} ))
last_log=0
degraded_streak=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  ph=""
  if IFS= read -r ph < <(kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o jsonpath="{.status.phase}" 2>/dev/null); then
    :
  fi
  ph=${ph:-}
  case "$ph" in
    Healthy)
      echo "Rollout Healthy."
      kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o wide || true
      exit 0
      ;;
    Failed)
      echo "Rollout Failed."
      kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o wide || true
      kubectl argo rollouts --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" 2>/dev/null | head -40 || true
      exit 1
      ;;
    Degraded)
      # Argo đôi khi giữ phase=Degraded + RolloutAborted dù stable đủ pod (canary RS scale về 0).
      # Không so ready với spec.replicas: env có thể 7 nhưng cluster đang 5 → mismatch vĩnh viễn.
      cur=$(kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o jsonpath='{.status.replicas}' 2>/dev/null || true)
      ready=$(kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
      upd=$(kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o jsonpath='{.status.updatedReplicas}' 2>/dev/null || true)
      desired=$(kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
      cur=${cur:-}; ready=${ready:-}; upd=${upd:-}; desired=${desired:-}
      [ -z "$upd" ] && upd=0
      ok=0
      if [ -n "$cur" ] && [ "$cur" != "0" ] && [ "$ready" = "$cur" ] && [ "$upd" = "0" ]; then
        ok=1
      elif [ -n "$desired" ] && [ "$ready" = "$desired" ] && [ "$upd" = "0" ]; then
        ok=1
      fi
      if [ "$ok" = "1" ]; then
        echo "INFO: phase=Degraded nhưng fleet ổn định (status.replicas=${cur} ready=${ready}, spec.replicas=${desired}, updatedReplicas=${upd}) — coi như promote xong."
        kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o wide || true
        exit 0
      fi
      degraded_streak=$((degraded_streak + 1))
      if [ "$degraded_streak" -ge 36 ]; then
        echo "Rollout Degraded liên tục ~3 phút (36 lần poll), dừng."
        kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o wide || true
        kubectl argo rollouts --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" 2>/dev/null | head -40 || true
        exit 1
      fi
      echo "WARN: phase=Degraded (lần $degraded_streak/36, có thể tạm sau promote), tiếp tục chờ..."
      ;;
    "")
      degraded_streak=0
      ;;
    *)
      degraded_streak=0
      now=$(date +%s)
      if [ $((now - last_log)) -ge 30 ]; then
        echo "... vẫn chờ (phase=$ph)"
        kubectl argo rollouts --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" 2>/dev/null | head -25 || true
        last_log=$now
      fi
      ;;
  esac
  sleep 5
done
echo "Timeout: chưa thấy rollout Healthy sau ${WRC_DEADLINE_SEC}s (phase cuối: ${ph:-unknown})"
kubectl --context "${WRC_CTX}" -n "${WRC_NS}" get rollout "${WRC_SVC}" -o wide || true
exit 1
'''
  }
}

/** Local copy: cần thiết vì waitRolloutComplete dùng parseTimeoutSeconds. */
def parseTimeoutSeconds(String raw) {
  String t = (raw ?: '600s').trim().toLowerCase()
  if (!t) {
    return 600
  }
  if (t ==~ /\d+/) {
    return t.toInteger()
  }
  if (t ==~ /\d+s/) {
    return t[0..-2].toInteger()
  }
  if (t ==~ /\d+m/) {
    return t[0..-2].toInteger() * 60
  }
  if (t ==~ /\d+h/) {
    return t[0..-2].toInteger() * 3600
  }
  error("ROLLOUT_WAIT_TIMEOUT không hợp lệ: '${raw}'. Dùng dạng 300s, 10m, 1h hoặc số giây.")
}

