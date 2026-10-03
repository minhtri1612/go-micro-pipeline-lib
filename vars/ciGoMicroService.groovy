def call(Map cfg = [:]) {
  // DevOps-owned CI. Dev only passes service identity from Jenkinsfile.
  def service = (cfg.service ?: error('ciGoMicroService: missing service')).toString().trim()
  def imageRepo = (cfg.imageRepo ?: error('ciGoMicroService: missing imageRepo')).toString().trim()
  def gitopsRepo = (cfg.gitopsRepo ?: 'https://github.com/minhtri1612/go-micro-gitops.git').toString().trim()
  def gitBranch = (cfg.gitBranch ?: 'main').toString().trim()
  def envKey = (service in ['notification', 'noti']) ? 'noti' : service

  pipeline {
    agent none
    options {
      timestamps()
      disableConcurrentBuilds()
    }
    parameters {
      choice(
        name: 'TARGET_ENV',
        choices: ['dev', 'prod'],
        description: 'dev = rebuild + push env/dev.yaml. prod = open GitOps PR for env/prod.yaml (merge on GitHub); no rebuild.'
      )
    }
    stages {
      stage('Identify') {
        agent any
        steps {
          script {
            def targetEnv = (params.TARGET_ENV ?: 'dev').toString().trim().toLowerCase()
            if (!(targetEnv in ['dev', 'prod'])) {
              targetEnv = 'dev'
            }
            def onMain = isGitopsBumpBranch()
            if (targetEnv == 'prod' && !onMain) {
              echo "TARGET_ENV=prod ignored on branch ${env.BRANCH_NAME} — GitOps bump stays off."
              targetEnv = 'dev'
            }
            def clickers = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
            def userId = (clickers && !clickers.isEmpty()) ? clickers[0].userId : null
            if (targetEnv == 'prod') {
              if (!userId) {
                echo 'TARGET_ENV=prod ignored on webhook/SCM — dev merge only writes env/dev.yaml.'
                targetEnv = 'dev'
              } else {
                def adminId = (System.getenv('JENKINS_ADMIN_ID') ?: 'admin').toString()
                if (userId != adminId) {
                  error("prod is DevOps only (${adminId}). Developer ${userId} stops at env/dev.yaml.")
                }
              }
            }
            env.CI_TARGET_ENV = targetEnv
            env.CI_PROMOTE_ONLY = (targetEnv == 'prod' && onMain) ? '1' : '0'
            env.CI_SERVICE = service
            env.CI_IMAGE_REPO = imageRepo
            env.CI_ENV_KEY = envKey
            env.CI_ENV_FILE = targetEnv == 'prod' ? 'env/prod.yaml' : 'env/dev.yaml'
            env.CI_GITOPS_REPO = gitopsRepo
            env.CI_GITOPS_BRANCH = gitBranch
            env.CI_GIT_SHA = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
            env.CI_IMAGE_NAME = imageRepo.tokenize('/')[-1]
            env.CI_FULL_TAG = "${env.CI_IMAGE_NAME}-${env.CI_GIT_SHA}"
            env.CI_BUMP_GITOPS = onMain ? '1' : '0'
            env.CI_SKIP_ROLLOUT_GATE = '0'
            env.CI_KUBE_CONTEXT = targetEnv == 'prod' ? 'prod' : 'dev'
            env.CI_ROLLOUT_NS = targetEnv == 'prod' ? 'microservices-prod' : 'microservices-dev'
            env.CI_INGRESS_HOST = targetEnv == 'prod' ? 'go-micro.local' : 'dev.go-micro.local'
            echo "service=${service}  gitopsKey=${envKey}  tag=${env.CI_FULL_TAG}"
            echo "branch=${env.BRANCH_NAME} changeId=${env.CHANGE_ID} bumpGitops=${env.CI_BUMP_GITOPS} targetEnv=${targetEnv} promoteOnly=${env.CI_PROMOTE_ONLY}"
            echo "gitopsFile=${env.CI_ENV_FILE} kube=${env.CI_KUBE_CONTEXT} ns=${env.CI_ROLLOUT_NS} host=${env.CI_INGRESS_HOST}"
            echo "Dev: repo + Jenkinsfile. DevOps: this library + GitOps. CD: Argo CD. Prod: GitOps PR (not push main)."
          }
        }
      }
      stage('Build & Push') {
        when {
          beforeAgent true
          not { environment name: 'CI_PROMOTE_ONLY', value: '1' }
        }
        agent any
        steps {
          withCredentials([usernamePassword(
            credentialsId: 'dockerhub-credentials',
            usernameVariable: 'DOCKER_USER',
            passwordVariable: 'DOCKER_PASS'
          )]) {
            sh '''
              set -e
              echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
              docker run --privileged --rm tonistiigi/binfmt --install amd64
              export DOCKER_BUILDKIT=1
              docker build --platform linux/amd64 -t "${CI_IMAGE_REPO}:${CI_FULL_TAG}" .
              docker push "${CI_IMAGE_REPO}:${CI_FULL_TAG}"
            '''
          }
        }
      }
      stage('Bump GitOps') {
        when {
          beforeAgent true
          environment name: 'CI_BUMP_GITOPS', value: '1'
        }
        agent any
        steps {
          script {
            def gitopsHttps = env.CI_GITOPS_REPO.replace('https://', '')
            dir('gitops-checkout') {
              deleteDir()
              withCredentials([usernamePassword(
                credentialsId: 'github-go-micro-pat',
                usernameVariable: 'GH_USER',
                passwordVariable: 'GH_TOKEN'
              )]) {
                sh """
                  set -e
                  git clone --depth 1 --branch '${env.CI_GITOPS_BRANCH}' \
                    "https://x-access-token:\${GH_TOKEN}@${gitopsHttps}" .
                """
                if (env.CI_PROMOTE_ONLY == '1') {
                  def fromDev = readEnvTag(readFile('env/dev.yaml'), env.CI_ENV_KEY)
                  if (!fromDev) {
                    error("ciGoMicroService: no ${env.CI_ENV_KEY}.image.tag in env/dev.yaml — ship to dev first")
                  }
                  env.CI_FULL_TAG = fromDev
                  echo "Promote dev tag ${fromDev} → env/prod.yaml via GitOps PR (no rebuild, no push main)"
                }
                def yaml = readFile(env.CI_ENV_FILE)
                def patched = patchEnvTag(yaml, env.CI_ENV_KEY, env.CI_FULL_TAG)
                if (!patched.ok) {
                  error("ciGoMicroService: cannot find ${env.CI_ENV_KEY}.image.tag in ${env.CI_ENV_FILE}")
                }
                writeFile file: env.CI_ENV_FILE, text: patched.text
                sh '''
                  set -e
                  git config user.email 'jenkins@go-micro.local'
                  git config user.name 'jenkins-ci'
                '''
                if (env.CI_PROMOTE_ONLY == '1') {
                  pushGitopsPromotePr()
                } else {
                  sh """
                    set -e
                    git add '${env.CI_ENV_FILE}'
                    if git diff --cached --quiet; then
                      echo 'Nothing to commit in gitops'
                    else
                      git commit -m "ci: bump ${env.CI_ENV_KEY} in ${env.CI_ENV_FILE} [skip ci] #\${BUILD_NUMBER}"
                      git push origin "HEAD:${env.CI_GITOPS_BRANCH}"
                    fi
                  """
                }
              }
            }
          }
        }
      }
      stage('Wait canary') {
        when {
          beforeAgent true
          environment name: 'CI_BUMP_GITOPS', value: '1'
        }
        agent any
        steps {
          script {
            waitCanaryPaused()
          }
        }
      }
      stage('k6') {
        when {
          beforeAgent true
          allOf {
            environment name: 'CI_BUMP_GITOPS', value: '1'
            not { environment name: 'CI_SKIP_ROLLOUT_GATE', value: '1' }
          }
        }
        agent any
        steps {
          script {
            resolveBackendIp()
            try {
              runK6Test()
            } catch (err) {
              echo 'k6 failed — abort canary so it does not sit at 20%.'
              applyRollout('abort')
              throw err
            }
          }
        }
      }
      stage('Rollout') {
        when {
          beforeInput true
          allOf {
            environment name: 'CI_BUMP_GITOPS', value: '1'
            not { environment name: 'CI_SKIP_ROLLOUT_GATE', value: '1' }
          }
        }
        options {
          timeout(time: 30, unit: 'MINUTES')
        }
        input {
          message "Canary pause. TARGET_ENV=${params.TARGET_ENV}. Promote = tiếp 50→100. Abort = hủy canary."
          ok 'Chạy'
          parameters {
            choice(
              name: 'ROLLOUT_ACTION',
              choices: ['promote', 'abort'],
              description: 'Nút rollout trên Jenkins — không cần CLI.'
            )
          }
        }
        agent any
        steps {
          script {
            def action = (params.ROLLOUT_ACTION ?: env.ROLLOUT_ACTION)?.toString()?.trim()
            applyRollout(action)
          }
        }
      }
    }
    post {
      success {
        echo "CI done: ${env.CI_IMAGE_REPO ?: ''}:${env.CI_FULL_TAG ?: ''} bumpGitops=${env.CI_BUMP_GITOPS ?: ''}"
      }
    }
  }
}

def isGitopsBumpBranch() {
  if (env.CHANGE_ID?.trim()) {
    return false
  }
  def b = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').trim()
  if (!b) {
    return true
  }
  return b == 'main' || b == 'origin/main' || b.endsWith('/main')
}

def gitopsRepoSlug() {
  return env.CI_GITOPS_REPO.replace('https://github.com/', '').replace('.git', '').trim()
}

def pushGitopsPromotePr() {
  def branch = "ci/promote-${env.CI_ENV_KEY}-${env.CI_FULL_TAG}-${env.BUILD_NUMBER}"
    .replaceAll('[^A-Za-z0-9._/-]', '-')
  def slug = gitopsRepoSlug()
  def title = "ci: promote ${env.CI_ENV_KEY} ${env.CI_FULL_TAG} to env/prod.yaml"
  def body = "Jenkins ${env.JOB_NAME} #${env.BUILD_NUMBER}. Copy dev tag into env/prod.yaml. Merge this PR, then Sync Argo prod (Manual)."
  sh 'git add env/prod.yaml'
  def dirty = sh(script: 'git diff --cached --quiet && echo 0 || echo 1', returnStdout: true).trim() == '1'
  if (!dirty) {
    echo "env/prod.yaml already has ${env.CI_FULL_TAG} — no promote PR"
    return
  }
  sh """
    set -e
    git commit -m '${title} [skip ci] #${env.BUILD_NUMBER}'
    git checkout -B '${branch}'
    git push -u origin 'HEAD:${branch}'
  """
  sh """
    set -euo pipefail
    jq -n --arg t '${title}' --arg h '${branch}' --arg b '${body}' --arg base '${env.CI_GITOPS_BRANCH}' \
      '{title:\$t,head:\$h,base:\$base,body:\$b}' > pr-payload.json
    curl -sS -X POST -H "Authorization: Bearer \${GH_TOKEN}" -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${slug}/pulls" -d @pr-payload.json -o pr-response.json
  """
  def html = sh(script: 'jq -r ".html_url // empty" pr-response.json', returnStdout: true).trim()
  def num = sh(script: 'jq -r ".number // empty" pr-response.json', returnStdout: true).trim()
  if (!html || !num) {
    sh """
      set -euo pipefail
      curl -sS -H "Authorization: Bearer \${GH_TOKEN}" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${slug}/pulls?head=\${GH_USER}:${branch}&state=open" -o pr-existing.json
    """
    html = sh(script: 'jq -r ".[0].html_url // empty" pr-existing.json', returnStdout: true).trim()
    num = sh(script: 'jq -r ".[0].number // empty" pr-existing.json', returnStdout: true).trim()
  }
  if (!html || !num) {
    echo sh(script: 'head -c 1500 pr-response.json', returnStdout: true)
    error('ciGoMicroService: cannot open GitOps promote PR (PAT needs pull_requests write)')
  }
  env.CI_PROMOTE_PR = num
  echo "Prod GitOps PR ${html} — merge trên GitHub. Jenkins chờ merged rồi mới Wait canary."
  echo "Argo prod vẫn Manual — Sync sau khi PR vào main."
  waitGitopsPrMerged(slug, num)
}

def waitGitopsPrMerged(String slug, String num) {
  def merged = false
  int n = 0
  while (n < 60) {
    n++
    def st = sh(script: """
      set -euo pipefail
      curl -sS -H "Authorization: Bearer \${GH_TOKEN}" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${slug}/pulls/${num}" | jq -r '[.merged, .state] | @tsv'
    """, returnStdout: true).trim()
    echo "GitOps PR #${num}: ${st} (${n}/60)"
    def parts = st.split('\t') as String[]
    if (parts.length >= 1 && parts[0] == 'true') {
      merged = true
      break
    }
    if (parts.length >= 2 && parts[1] == 'closed') {
      error("GitOps promote PR #${num} closed without merge")
    }
    sleep 30
  }
  if (!merged) {
    error("GitOps promote PR #${num} not merged within 30m — merge it then Rebuild prod")
  }
  echo "GitOps promote PR #${num} merged"
}

def patchEnvTag(String yamlText, String envKey, String fullTag) {
  def out = new StringBuilder()
  def inBlock = false
  def patched = false
  yamlText.readLines().each { line ->
    if (line ==~ /^${java.util.regex.Pattern.quote(envKey)}:\s*/) {
      inBlock = true
      out.append(line).append('\n')
      return
    }
    if (inBlock && line ==~ /^[A-Za-z0-9_-]+:.*/) {
      inBlock = false
    }
    if (inBlock && !patched && line ==~ /^\s+tag:\s*.*/) {
      def indent = (line =~ /^(\s+)/)[0][1]
      out.append("${indent}tag: \"${fullTag}\"\n")
      patched = true
      return
    }
    out.append(line).append('\n')
  }
  return [ok: patched, text: out.toString()]
}

def readEnvTag(String yamlText, String envKey) {
  def inBlock = false
  def tag = ''
  yamlText.readLines().each { line ->
    if (line ==~ /^${java.util.regex.Pattern.quote(envKey)}:\s*/) {
      inBlock = true
      return
    }
    if (inBlock && line ==~ /^[A-Za-z0-9_-]+:.*/) {
      inBlock = false
    }
    if (inBlock && !tag && line ==~ /^\s+tag:\s*.*/) {
      def m = (line =~ /^\s+tag:\s*"?([^"\s]+)"?/)
      if (m) {
        tag = m[0][1]
      }
    }
  }
  return tag
}

def clusterKubectl(String args) {
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  return clusterSh("kubectl -n ${ns} ${args}")
}

def clusterSh(String inner) {
  def kubeContext = env.CI_KUBE_CONTEXT ?: 'dev'
  return sh(returnStdout: true, script: """
      set -euo pipefail
      export KUBECONFIG="\${KUBECONFIG:-/var/jenkins_home/.kube/${kubeContext}}"
      export PATH=/usr/local/bin:/usr/bin:\$PATH
      test -s "\$KUBECONFIG"
      ${inner}
    """).trim()
}

def waitCanaryPaused() {
  def svc = env.CI_ENV_KEY
  def tag = env.CI_FULL_TAG
  def deadline = System.currentTimeMillis() + (8L * 60L * 1000L)
  def retried = false
  echo "Waiting for rollout/${svc} image :${tag} to pause (Argo sync, then canary pause:{})."
  while (System.currentTimeMillis() < deadline) {
    def raw = clusterKubectl("get rollout ${svc} -o jsonpath='{.status.phase}|{.status.abort}|{.status.message}|{.spec.template.spec.containers[0].image}'")
    echo "rollout/${svc}: ${raw}"
    def parts = raw.split('\\|', 4)
    def phase = parts.length > 0 ? parts[0].trim() : ''
    def aborted = parts.length > 1 ? parts[1].trim() : ''
    def msg = parts.length > 2 ? parts[2].trim() : ''
    def img = parts.length > 3 ? parts[3].trim() : ''
    def tagHit = img.contains(":${tag}")
    if (phase in ['Failed']) {
      error("Rollout ${svc} phase=${phase} image=${img} ${msg}")
    }
    if (tagHit && phase == 'Paused') {
      echo 'Canary paused. Next: k6, then Jenkins Promote/Abort.'
      return
    }
    if (tagHit && phase == 'Healthy') {
      echo 'Rollout already Healthy on this tag (no pause). Skipping Jenkins Promote button.'
      env.CI_SKIP_ROLLOUT_GATE = '1'
      return
    }
    if (tagHit && aborted == 'true' && !retried) {
      echo "Rollout aborted on :${tag} (${msg}). Same GitOps tag — retry canary."
      applyRollout('retry')
      retried = true
    }
    sleep(time: 15, unit: 'SECONDS')
  }
  error("Timeout waiting for canary pause on rollout/${svc} tag=${tag}. Check Argo app.")
}

def applyRollout(String action) {
  if (!(action in ['promote', 'abort', 'retry'])) {
    error("Unknown ROLLOUT_ACTION='${action}'. Use promote, abort, or retry.")
  }
  def svc = env.CI_ENV_KEY
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  echo "Jenkins button → ${action} rollout/${svc}"
  clusterSh("""set -e
if kubectl argo rollouts version >/dev/null 2>&1; then
  if [ "${action}" = "retry" ]; then
    kubectl argo rollouts -n ${ns} retry rollout ${svc}
  else
    kubectl argo rollouts -n ${ns} ${action} ${svc}
  fi
else
  echo "kubectl-argo-rollouts missing on Jenkins" >&2
  exit 1
fi
kubectl -n ${ns} get rollout ${svc}
""")
}

def resolveBackendIp() {
  if (env.CI_BACKEND_IP?.trim()) {
    echo "BACKEND_IP=${env.CI_BACKEND_IP} (already set)"
    return
  }
  def ip = clusterSh("""set -e
kubectl -n traefik get svc traefik -o jsonpath='{.spec.clusterIP}'
""").trim()
  if (!ip) {
    error('Cannot resolve Traefik ClusterIP (namespace traefik, svc traefik).')
  }
  env.CI_BACKEND_IP = ip
  echo "BACKEND_IP=${ip}  Host=${env.CI_INGRESS_HOST ?: 'dev.go-micro.local'}"
}

def runK6Test() {
  def svc = env.CI_ENV_KEY
  def ip = env.CI_BACKEND_IP
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  def pod = "ci-k6-${env.BUILD_NUMBER}".toLowerCase().replaceAll('[^a-z0-9-]', '').take(50)
  def host = env.CI_INGRESS_HOST ?: 'dev.go-micro.local'
  echo "k6 → pod/${pod} ${svc} ${ip} Host=${host}"
  clusterSh("""set -e
NS=${ns}
POD=${pod}
kubectl -n \$NS delete pod \$POD --ignore-not-found --wait=false >/dev/null 2>&1 || true
sleep 2
kubectl -n \$NS run \$POD --restart=Never --image=grafana/k6:0.49.0 --command -- \\
  sh -lc 'cat >/tmp/k6.js <<EOF
import http from "k6/http";
import { check, sleep } from "k6";
const service = __ENV.SERVICE_NAME || "order";
const target = __ENV.TARGET_URL || "localhost";
const params = { headers: { Host: "${host}", "Content-Type": "application/json" } };
export const options = {
  stages: [
    { duration: "5s", target: 200 },
    { duration: "5s", target: 500 },
    { duration: "10s", target: 1000 },
  ],
  thresholds: { http_req_failed: ["rate<0.001"] },
};
function ok(r) { return r.status === 200 || r.status === 201; }
export default function () {
  const base = "http://" + target;
  const id = __VU + "-" + __ITER;
  check(http.get(base + ({ product:"/api/v1/products", order:"/api/v1/orders", inventory:"/api/v1/inventory", noti:"/api/v1/notifications", payment:"/api/v1/payments/order/1", client:"/" }[service] || "/api/v1/products"), params), { "get 200": (r) => r.status === 200 });
  if (service === "product") { check(http.post(base + "/api/v1/products", JSON.stringify({ name: "k6-" + id, description: "canary", price: 1 }), params), { "write 2xx": ok }); }
  else if (service === "inventory") { check(http.post(base + "/api/v1/inventory", JSON.stringify({ product_id: 1, quantity: 1, sku: "k6-" + id, location: "k6" }), params), { "write 2xx": ok }); }
  else if (service === "noti") { check(http.post(base + "/api/v1/notifications", JSON.stringify({ order_id: 1, customer_id: 1, message: "k6-" + id, status: "pending" }), params), { "write 2xx": ok }); }
  sleep(0.3);
}
EOF
TARGET_URL=${ip} SERVICE_NAME=${svc} k6 run /tmp/k6.js
'
for i in \$(seq 1 120); do
  PH=\$(kubectl -n \$NS get pod \$POD -o jsonpath="{.status.phase}" 2>/dev/null || echo Pending)
  echo "pod \$POD phase=\$PH"
  case "\$PH" in
    Succeeded)
      kubectl -n \$NS logs \$POD
      kubectl -n \$NS delete pod \$POD --ignore-not-found >/dev/null
      exit 0
      ;;
    Failed)
      kubectl -n \$NS logs \$POD || true
      kubectl -n \$NS delete pod \$POD --ignore-not-found >/dev/null
      exit 1
      ;;
  esac
  sleep 2
done
kubectl -n \$NS logs \$POD || true
kubectl -n \$NS describe pod \$POD || true
kubectl -n \$NS delete pod \$POD --ignore-not-found >/dev/null
exit 1
""")
}
