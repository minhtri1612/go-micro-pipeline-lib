// Tests + prepare helpers: k8s node check, dependency check, business smoke, route smoke, k6 load.
// Cũng chứa helpers để resolve rollout service / route prefix / canary header detection (dùng ở stage Prepare).

def getEffectiveDependencyServices() {
  return env.EFFECTIVE_DEPENDENCY_SERVICES?.trim() ?: params.DEPENDENCY_SERVICES
}

def getEffectiveBusinessServices() {
  return env.EFFECTIVE_BUSINESS_SERVICES?.trim() ?: params.BUSINESS_SERVICES
}

def expandServicesCsv(String raw, List allList) {
  if (raw == null || !raw.trim()) {
    error('Thiếu DEPENDENCY_SERVICES / BUSINESS_SERVICES (dùng `all` hoặc CSV).')
  }
  def t = raw.trim()
  if (t.equalsIgnoreCase('all')) {
    return allList
  }
  def out = t.split(',').collect { it.trim().toLowerCase() }.findAll { it.length() > 0 }.unique()
  if (out.isEmpty()) {
    error('Danh sách service sau khi parse rỗng.')
  }
  return out
}

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
  error("POD_WAIT_TIMEOUT không hợp lệ: '${raw}'. Dùng dạng 300s, 10m, 1h hoặc số giây.")
}

def runInDevPod(String kubeContext, String namespace, String image, String scriptBody) {
  String podName = "ci-${env.BUILD_NUMBER}-${java.util.UUID.randomUUID().toString().take(8)}".toLowerCase()
  String escaped = scriptBody.stripIndent().trim().replace("'", "'\"'\"'")
  String timeout = (params.POD_WAIT_TIMEOUT ?: '600s').trim()
  String serviceAccount = (params.DEV_TEST_SERVICE_ACCOUNT ?: 'default').trim()
  int timeoutSeconds = parseTimeoutSeconds(timeout)
  sh """#!/bin/sh
    set -e
    kubectl --context ${kubeContext} -n ${namespace} run ${podName} --image=${image} --restart=Never --overrides='{"apiVersion":"v1","spec":{"serviceAccountName":"${serviceAccount}"}}' --command -- sh -lc '${escaped}'
    START=\$(date +%s)
    LASTLOG=\$START
    ITER=0
    while true; do
      ITER=\$((ITER + 1))
      PHASE=\$(kubectl --context ${kubeContext} -n ${namespace} get pod/${podName} -o jsonpath='{.status.phase}' 2>/dev/null | awk 'NR==1{gsub(\"\\r\",\"\"); gsub(\"\\n\",\"\"); print; exit}' || true)
      PHASE=\${PHASE:-Unknown}
      if [ "\$PHASE" = "Succeeded" ] || [ "\$PHASE" = "Failed" ]; then
        break
      fi
      NOW=\$(date +%s)
      if [ \$((NOW-START)) -ge ${timeoutSeconds} ]; then
        echo "Pod ${podName} timeout after ${timeout} (current phase=\$PHASE)"
        PHASE="Timeout"
        break
      fi
      if [ \$((NOW-LASTLOG)) -ge 30 ]; then
        echo "[wait pod ${podName}] phase=\$PHASE elapsed=\$((NOW-START))s iter=\$ITER"
        LASTLOG=\$NOW
      fi
      sleep 2
    done
    kubectl --context ${kubeContext} -n ${namespace} logs ${podName} || true
    PHASE=\$(kubectl --context ${kubeContext} -n ${namespace} get pod/${podName} -o jsonpath='{.status.phase}' 2>/dev/null | awk 'NR==1{gsub(\"\\r\",\"\"); gsub(\"\\n\",\"\"); print; exit}' || true)
    PHASE=\${PHASE:-Unknown}
    if [ "\$PHASE" != "Succeeded" ]; then
      echo "Pod ${podName} ended with phase \$PHASE (timeout=${timeout})"
      kubectl --context ${kubeContext} -n ${namespace} describe pod/${podName} || true
      kubectl --context ${kubeContext} -n ${namespace} delete pod/${podName} --ignore-not-found=true >/dev/null
      exit 1
    fi
    kubectl --context ${kubeContext} -n ${namespace} delete pod/${podName} --ignore-not-found=true >/dev/null
  """
}

def runWithMode(String mode, String kubeContext, String namespace, String image, String podScriptBody, String directScriptBody) {
  if (mode == 'dev-pod') {
    runInDevPod(kubeContext, namespace, image, podScriptBody)
    return
  }
  sh """
    set -e
    ${directScriptBody}
  """
}

/** Apk + pip inside ephemeral python-alpine pods. Alpine apk mirrors ổn hơn apt trong kind cluster. */
def devPodAptBootstrapShell() {
  return '''set -e
# Alpine: apk nhanh + ít bị mất kết nối hơn apt trong kind/flannel.
ok=0
for attempt in 1 2 3 4; do
  if apk add --no-cache git ca-certificates 2>&1; then
    ok=1
    break
  fi
  echo "[WARN] apk add failed (attempt $attempt/4); retry in 10s..." >&2
  sleep 10
done
if [ "$ok" != "1" ]; then
  echo "[FATAL] apk could not install git after 4 attempts" >&2
  exit 1
fi
command -v git >/dev/null
pip install --no-cache-dir requests
'''
}

/** Shell snippet: clone repo into /tmp/go-micro (retries; do not swallow errors — parallel pods hammer GitHub). */
def devPodCloneRepoShell() {
  return '''export GIT_TERMINAL_PROMPT=0
rm -rf /tmp/go-micro
for i in 1 2 3 4 5; do
  if git clone --depth 1 https://github.com/minhtri1612/go-micro.git /tmp/go-micro; then
    break
  fi
  echo "[WARN] git clone failed, retry in 12s ($i/5)"
  sleep 12
done
test -d /tmp/go-micro/tests || { echo "[FATAL] clone missing tests/"; ls -la /tmp || true; exit 1; }
cd /tmp/go-micro'''
}

def runDependencyCheckSteps() {
  def allDep = ['product', 'inventory', 'order', 'payment', 'noti']
  def svcs = expandServicesCsv(getEffectiveDependencyServices(), allDep)
  svcs.each { svc ->
    def canaryHeader = shouldUseCanaryHeaderForService(svc) ? 'true' : 'false'
    runWithMode(
      env.EFFECTIVE_EXECUTION_MODE,
      params.DEV_KUBE_CONTEXT,
      params.DEV_TEST_NAMESPACE,
      'python:3.11-alpine',
      """
        ${devPodAptBootstrapShell()}
        ${devPodCloneRepoShell()}
        CANARY_HEADER=${canaryHeader} python3 tests/dependency-check/run.py ${svc} ${params.BACKEND_IP}
      """,
      "CANARY_HEADER=${canaryHeader} python3 tests/dependency-check/run.py ${svc} ${params.BACKEND_IP}"
    )
  }
}

def runBusinessSmokeSteps() {
  def allBiz = ['product', 'inventory', 'order', 'payment', 'noti']
  def svcs = expandServicesCsv(getEffectiveBusinessServices(), allBiz)
  svcs.each { svc ->
    def canaryHeader = shouldUseCanaryHeaderForService(svc) ? 'true' : 'false'
    runWithMode(
      env.EFFECTIVE_EXECUTION_MODE,
      params.DEV_KUBE_CONTEXT,
      params.DEV_TEST_NAMESPACE,
      'python:3.11-alpine',
      """
        ${devPodAptBootstrapShell()}
        ${devPodCloneRepoShell()}
        CANARY_HEADER=${canaryHeader} python3 tests/business-smoke/run.py ${svc} ${params.BACKEND_IP}
      """,
      "CANARY_HEADER=${canaryHeader} python3 tests/business-smoke/run.py ${svc} ${params.BACKEND_IP}"
    )
  }
}

def runRouteSmokeSteps() {
  runWithMode(
    env.EFFECTIVE_EXECUTION_MODE,
    params.DEV_KUBE_CONTEXT,
    params.DEV_TEST_NAMESPACE,
    'python:3.11-alpine',
    """
      ${devPodAptBootstrapShell()}
      ${devPodCloneRepoShell()}
      python3 tests/route-smoke-test/run.py ${params.TARGET_HOST} ${env.EFFECTIVE_ROUTE_PREFIX} ${params.ROUTE_MODE} ${params.BACKEND_IP}
    """,
    "python3 tests/route-smoke-test/run.py ${params.TARGET_HOST} ${env.EFFECTIVE_ROUTE_PREFIX} ${params.ROUTE_MODE} ${params.BACKEND_IP}"
  )
}

def runK8sNodeCheckSteps() {
  runWithMode(
    env.EFFECTIVE_EXECUTION_MODE,
    params.DEV_KUBE_CONTEXT,
    params.DEV_TEST_NAMESPACE,
    'python:3.11-alpine',
    """
      ${devPodAptBootstrapShell()}
      ${devPodCloneRepoShell()}
      python3 tests/k8s-node-check/run.py
    """,
    """
      pip3 install requests >/dev/null 2>&1 || true
      python3 tests/k8s-node-check/run.py
    """
  )
}

def runK6LoadSteps() {
  runWithMode(
    env.EFFECTIVE_EXECUTION_MODE,
    params.DEV_KUBE_CONTEXT,
    params.DEV_TEST_NAMESPACE,
    'grafana/k6:0.49.0',
    """
      cat >/tmp/k6-script.js <<'EOF'
      import http from 'k6/http';
      import { check, sleep } from 'k6';
      const vus = __ENV.VUS || 10;
      const duration = __ENV.DURATION || '30s';
      const errorRate = __ENV.ERROR_RATE || '0.1';
      const service = __ENV.SERVICE_NAME || 'product';
      const target = __ENV.TARGET_URL || 'localhost';
      export const options = { vus: vus, duration: duration, thresholds: { http_req_failed: ['rate<' + errorRate], http_req_duration: ['p(95)<2000'] } };
      function getPrefix(s) { return ({ product:'/api/v1/products', order:'/api/v1/orders', inventory:'/api/v1/inventory', noti:'/api/v1/notifications', payment:'/api/v1/payments', client:'/' }[s] || '/api/v1/products'); }
      function probePath(s) { if (s === 'client') return '/'; if (s === 'order') return '/orders'; return '/health'; }
      export default function () { const prefix = getPrefix(service); const path = probePath(service); const params = { headers: { Host: 'dev.go-micro.local' } }; const res = http.get('http://' + target + prefix + path, params); check(res, { 'status is 200': (r) => r.status === 200 }); sleep(0.3); }
      EOF
      TARGET_URL=${params.BACKEND_IP} SERVICE_NAME=${params.K6_SERVICE_NAME} VUS=${params.K6_VUS} DURATION=${params.K6_DURATION} ERROR_RATE=${params.K6_ERROR_RATE} k6 run /tmp/k6-script.js
    """,
    """docker run --rm \
      -e TARGET_URL=${params.BACKEND_IP} \
      -e SERVICE_NAME=${params.K6_SERVICE_NAME} \
      -e VUS=${params.K6_VUS} \
      -e DURATION=${params.K6_DURATION} \
      -e ERROR_RATE=${params.K6_ERROR_RATE} \
      -v "\$(pwd)/tests/load-test/k6-script.js:/scripts/k6-script.js:ro" \
      grafana/k6:0.49.0 run /scripts/k6-script.js"""
  )
}

def shouldEnableCanaryHeaderForApiTests() {
  return params.CANARY_HEADER_API_TESTS == true || params.CANARY_HEADER_API_TESTS == 'true'
}

def shouldUseCanaryHeaderForService(String svc) {
  if (shouldEnableCanaryHeaderForApiTests() || env.AUTO_CANARY_HEADER_FOR_TESTS == 'true') {
    return true
  }
  return serviceNeedsCanaryHeaderForTests(svc)
}

def serviceEndpointsReady(String kubeContext, String namespace, String serviceName) {
  if (!serviceName?.trim()) {
    return false
  }
  def ips = sh(
    script: "kubectl --context ${kubeContext} -n ${namespace} get endpoints ${serviceName} -o jsonpath='{.subsets[0].addresses[*].ip}' 2>/dev/null || true",
    returnStdout: true
  ).trim()
  return ips.length() > 0
}

def serviceNeedsCanaryHeaderForTests(String svc) {
  def ns = params.ROLLOUT_NAMESPACE?.trim()
  def ctx = params.DEV_KUBE_CONTEXT?.trim()
  if (!svc?.trim() || !ns || !ctx) {
    return false
  }
  def stableReady = serviceEndpointsReady(ctx, ns, svc)
  if (stableReady) {
    return false
  }
  def canaryReady = serviceEndpointsReady(ctx, ns, "${svc}-canary")
  if (!canaryReady) {
    return false
  }
  def phase = sh(
    script: "kubectl --context ${ctx} -n ${ns} get rollout ${svc} -o jsonpath='{.status.phase}' 2>/dev/null || true",
    returnStdout: true
  ).trim()
  if (phase in ['Paused', 'Progressing']) {
    echo "auto X-Canary: rollout/${svc} phase=${phase}, stable Endpoints trống, canary có pod — test trước Promote gate."
    return true
  }
  return false
}

def detectAutoCanaryHeaderForTests() {
  def probe = env.RESOLVED_ROLLOUT_SERVICE?.trim()
  if (probe) {
    return serviceNeedsCanaryHeaderForTests(probe)
  }
  def dep = getEffectiveDependencyServices()?.trim()?.toLowerCase()
  if (dep && dep != 'all' && !dep.contains(',')) {
    return serviceNeedsCanaryHeaderForTests(dep)
  }
  return false
}

def validateKubeContext(String kubeContext) {
  String contextsRaw = sh(
    script: "kubectl config get-contexts -o name || true",
    returnStdout: true
  ).trim()
  List contexts = contextsRaw ? contextsRaw.split('\n').collect { it.trim() } : []
  if (!contexts.contains(kubeContext.trim())) {
    error("Không tìm thấy context '${kubeContext}' trong Jenkins KUBECONFIG=${env.KUBECONFIG}. Context hiện có: ${contextsRaw ?: '(none)'}.")
  }
}

def serviceToRoutePrefix(String svc) {
  if (!svc?.trim()) {
    return '/api/v1/products'
  }
  switch (svc.trim().toLowerCase()) {
    case 'product':
      return '/api/v1/products'
    case 'order':
      return '/api/v1/orders'
    case 'inventory':
      return '/api/v1/inventory'
    case 'noti':
      return '/api/v1/notifications'
    case 'payment':
      return '/api/v1/payments'
    default:
      return '/api/v1/products'
  }
}

def resolveEffectiveRoutePrefix(String routeParam, String depServices, String bizServices) {
  def p = routeParam?.trim()
  if (p && !p.equalsIgnoreCase('auto')) {
    return p
  }
  def b = bizServices?.trim()?.toLowerCase()
  def d = depServices?.trim()?.toLowerCase()
  if (b && b != 'all') {
    return serviceToRoutePrefix(b)
  }
  if (d && d != 'all') {
    return serviceToRoutePrefix(d)
  }
  return '/api/v1/products'
}

def resolveRolloutService(String rolloutService, String depServices, String bizServices) {
  if (rolloutService != null && rolloutService.trim() && !rolloutService.trim().equalsIgnoreCase('auto')) {
    return rolloutService.trim().toLowerCase()
  }
  def candidates = [] as Set
  [depServices, bizServices].each { raw ->
    if (!raw) {
      return
    }
    def t = raw.trim().toLowerCase()
    if (t == 'all') {
      return
    }
    t.split(',').collect { it.trim() }.findAll { it }.each { candidates << it }
  }
  if (candidates.size() == 1) {
    return candidates.first()
  }
  echo "Không thể auto-resolve rollout service (DEP='${depServices}', BIZ='${bizServices}')."
  echo "Đặt ROLLOUT_SERVICE=<service> để bật promote/abort tự động."
  return ''
}

