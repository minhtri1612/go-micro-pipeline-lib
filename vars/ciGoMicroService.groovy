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
        description: 'Auto/merge = dest. Prod = Build with Parameters on main only; copy tag from env/dev.yaml, no rebuild.'
      )
    }
    stages {
      stage('Identify') {
        agent any
        steps {
          script {
            def dest = (params.TARGET_ENV ?: 'dev').toString().trim().toLowerCase()
            if (!(dest in ['dev', 'prod'])) {
              dest = 'dev'
            }
            def onMain = isGitopsBumpBranch()
            if (dest == 'prod' && !onMain) {
              echo "TARGET_ENV=prod ignored on branch ${env.BRANCH_NAME} — GitOps bump stays off."
              dest = 'dev'
            }
            env.CI_TARGET_ENV = dest
            env.CI_PROMOTE_ONLY = (dest == 'prod' && onMain) ? '1' : '0'
            env.CI_SERVICE = service
            env.CI_IMAGE_REPO = imageRepo
            env.CI_ENV_KEY = envKey
            env.CI_ENV_FILE = dest == 'prod' ? 'env/prod.yaml' : 'env/dev.yaml'
            env.CI_GITOPS_REPO = gitopsRepo
            env.CI_GITOPS_BRANCH = gitBranch
            env.CI_GIT_SHA = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
            env.CI_IMAGE_NAME = imageRepo.tokenize('/')[-1]
            env.CI_FULL_TAG = "${env.CI_IMAGE_NAME}-${env.CI_GIT_SHA}"
            env.CI_BUMP_GITOPS = onMain ? '1' : '0'
            env.CI_SKIP_ROLLOUT_GATE = '0'
            env.CI_KUBE_CONTEXT = dest == 'prod' ? 'kind-prod' : 'kind-dev'
            env.CI_ROLLOUT_NS = dest == 'prod' ? 'microservices-prod' : 'microservices-dev'
            env.CI_INGRESS_HOST = dest == 'prod' ? 'go-micro.local' : 'dev.go-micro.local'
            echo "service=${service}  gitopsKey=${envKey}  tag=${env.CI_FULL_TAG}"
            echo "branch=${env.BRANCH_NAME} changeId=${env.CHANGE_ID} bumpGitops=${env.CI_BUMP_GITOPS} targetEnv=${dest} promoteOnly=${env.CI_PROMOTE_ONLY}"
            echo "gitopsFile=${env.CI_ENV_FILE} kube=${env.CI_KUBE_CONTEXT} ns=${env.CI_ROLLOUT_NS} host=${env.CI_INGRESS_HOST}"
            echo "Dev: repo + Jenkinsfile. DevOps: this library + GitOps. CD: Argo CD. Prod: Build with Parameters on main."
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
                    error("ciGoMicroService: no ${env.CI_ENV_KEY}.image.tag in env/dev.yaml — ship dest first")
                  }
                  env.CI_FULL_TAG = fromDev
                  echo "Promote dest tag ${fromDev} → env/prod.yaml (no rebuild)"
                  echo "Argo prod is Manual — Sync the service app so Wait canary can see pause:{}."
                }
                def yaml = readFile(env.CI_ENV_FILE)
                def patched = patchEnvTag(yaml, env.CI_ENV_KEY, env.CI_FULL_TAG)
                if (!patched.ok) {
                  error("ciGoMicroService: cannot find ${env.CI_ENV_KEY}.image.tag in ${env.CI_ENV_FILE}")
                }
                writeFile file: env.CI_ENV_FILE, text: patched.text
                def commitMsg = env.CI_PROMOTE_ONLY == '1' ?
                  "ci: promote ${env.CI_ENV_KEY} ${env.CI_FULL_TAG} to ${env.CI_ENV_FILE} [skip ci] #${env.BUILD_NUMBER}" :
                  "ci: bump ${env.CI_ENV_KEY} in ${env.CI_ENV_FILE} [skip ci] #${env.BUILD_NUMBER}"
                sh """
                  set -e
                  git config user.email 'jenkins@go-micro.local'
                  git config user.name 'jenkins-ci'
                  git add '${env.CI_ENV_FILE}'
                  if git diff --cached --quiet; then
                    echo 'Nothing to commit in gitops'
                  else
                    git commit -m '${commitMsg}'
                    git push origin "HEAD:${env.CI_GITOPS_BRANCH}"
                  fi
                """
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
      stage('Parallel tests') {
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
            def branches = [:]
            branches['failFast'] = true
            branches['dependency'] = {
              runPyTest('dependency-check')
            }
            branches['business'] = {
              runPyTest('business-smoke')
            }
            branches['k6'] = {
              runK6Test()
            }
            try {
              parallel branches
            } catch (err) {
              echo 'Parallel tests failed — abort canary so it does not sit at 20%.'
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

def kindKubectl(String args) {
  def ctx = env.CI_KUBE_CONTEXT ?: 'kind-dev'
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  return ssmOnKind("""set -e
export KUBECONFIG=/root/.kube/config
export PATH=/usr/local/bin:/usr/bin:\$PATH
kubectl --context ${ctx} -n ${ns} ${args}
""")
}

def ssmOnKind(String inner, int timeoutSec = 90) {
  int polls = 45
  int extra = (timeoutSec.intdiv(2)) + 5
  if (extra > polls) {
    polls = extra
  }
  withEnv([
    'KIND_INNER_CMD=' + inner,
    "KIND_SSM_TIMEOUT=${timeoutSec}",
    "KIND_SSM_POLLS=${polls}",
    "AWS_ACCESS_KEY_ID=${env.AWS_APPLY_ACCESS_KEY_ID ?: env.AWS_ACCESS_KEY_ID}",
    "AWS_SECRET_ACCESS_KEY=${env.AWS_APPLY_SECRET_ACCESS_KEY ?: env.AWS_SECRET_ACCESS_KEY}",
    "AWS_DEFAULT_REGION=${env.AWS_DEFAULT_REGION ?: 'ap-southeast-2'}",
  ]) {
    return sh(returnStdout: true, script: '''
      set -euo pipefail
      : "${KIND_INSTANCE_ID:?Set KIND_INSTANCE_ID in jenkins/.env (Kind EC2 id), then docker compose up -d --build --force-recreate}"
      command -v aws >/dev/null
      command -v jq >/dev/null
      B64=$(printf '%s' "$KIND_INNER_CMD" | base64 -w 0)
      PAYLOAD=$(jq -n --arg c "echo $B64 | base64 -d | sudo bash -s" '{commands:[$c]}')
      CID=$(aws ssm send-command \
        --instance-ids "$KIND_INSTANCE_ID" \
        --document-name AWS-RunShellScript \
        --timeout-seconds "$KIND_SSM_TIMEOUT" \
        --parameters "$PAYLOAD" \
        --query Command.CommandId --output text)
      ST=Pending
      i=0
      while [ "$i" -lt "$KIND_SSM_POLLS" ]; do
        i=$((i+1))
        ST=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$KIND_INSTANCE_ID" --query Status --output text 2>/dev/null || echo Pending)
        case "$ST" in Success|Failed|Cancelled|TimedOut) break ;; esac
        sleep 2
      done
      OUT=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$KIND_INSTANCE_ID" --query StandardOutputContent --output text)
      ERR=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$KIND_INSTANCE_ID" --query StandardErrorContent --output text)
      printf '%s' "$OUT"
      if [ "$ST" != Success ]; then
        echo "SSM $ST" >&2
        echo "$ERR" >&2
        exit 1
      fi
    ''').trim()
  }
}

def waitCanaryPaused() {
  def svc = env.CI_ENV_KEY
  def tag = env.CI_FULL_TAG
  def deadline = System.currentTimeMillis() + (8L * 60L * 1000L)
  echo "Waiting for rollout/${svc} image :${tag} to pause (Argo sync, then canary pause:{})."
  while (System.currentTimeMillis() < deadline) {
    def raw = kindKubectl("get rollout ${svc} -o jsonpath='{.status.phase}|{.spec.template.spec.containers[0].image}'")
    echo "rollout/${svc}: ${raw}"
    def phase = ''
    def img = ''
    def pipe = raw.indexOf('|')
    if (pipe >= 0) {
      phase = raw.substring(0, pipe).trim()
      img = raw.substring(pipe + 1).trim()
    } else {
      phase = raw.trim()
    }
    def tagHit = img.contains(":${tag}")
    if (phase in ['Failed']) {
      error("Rollout ${svc} phase=${phase} image=${img}")
    }
    if (tagHit && phase == 'Paused') {
      echo 'Canary paused. Next: Parallel tests (canary header), then Jenkins Promote/Abort.'
      return
    }
    if (tagHit && phase == 'Healthy') {
      echo 'Rollout already Healthy on this tag (no pause). Skipping Jenkins Promote button.'
      env.CI_SKIP_ROLLOUT_GATE = '1'
      return
    }
    sleep(time: 15, unit: 'SECONDS')
  }
  error("Timeout waiting for canary pause on rollout/${svc} tag=${tag}. Check Argo app on Kind.")
}

def applyRollout(String action) {
  if (!(action in ['promote', 'abort'])) {
    error("Unknown ROLLOUT_ACTION='${action}'. Use promote or abort.")
  }
  def svc = env.CI_ENV_KEY
  def ctx = env.CI_KUBE_CONTEXT ?: 'kind-dev'
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  echo "Jenkins button → ${action} rollout/${svc}"
  ssmOnKind("""set -e
export KUBECONFIG=/root/.kube/config
export PATH=/usr/local/bin:/usr/bin:\$PATH
if kubectl argo rollouts --context ${ctx} version >/dev/null 2>&1; then
  kubectl argo rollouts --context ${ctx} -n ${ns} ${action} ${svc}
else
  echo "kubectl-argo-rollouts missing on Kind host" >&2
  exit 1
fi
kubectl --context ${ctx} -n ${ns} get rollout ${svc}
""")
}

def resolveBackendIp() {
  if (env.CI_BACKEND_IP?.trim()) {
    echo "BACKEND_IP=${env.CI_BACKEND_IP} (already set)"
    return
  }
  def ctx = env.CI_KUBE_CONTEXT ?: 'kind-dev'
  def ip = ssmOnKind("""set -e
export KUBECONFIG=/root/.kube/config
export PATH=/usr/local/bin:/usr/bin:\$PATH
kubectl --context ${ctx} -n traefik get svc traefik -o jsonpath='{.spec.clusterIP}'
""").trim()
  if (!ip) {
    error('Cannot resolve Traefik ClusterIP (namespace traefik, svc traefik).')
  }
  env.CI_BACKEND_IP = ip
  echo "BACKEND_IP=${ip}  Host=${env.CI_INGRESS_HOST ?: 'dev.go-micro.local'}  X-Canary=true"
}

def runPyTest(String suite) {
  def svc = env.CI_ENV_KEY
  def ip = env.CI_BACKEND_IP
  def ctx = env.CI_KUBE_CONTEXT ?: 'kind-dev'
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  def pod = "ci-${suite}-${env.BUILD_NUMBER}".toLowerCase().replaceAll('[^a-z0-9-]', '').take(50)
  def host = env.CI_INGRESS_HOST ?: 'dev.go-micro.local'
  echo "Parallel ${suite} → pod/${pod} ${svc} ${ip} Host=${host}"
  ssmOnKind("""set -e
export KUBECONFIG=/root/.kube/config
export PATH=/usr/local/bin:/usr/bin:\$PATH
CTX=${ctx}
NS=${ns}
POD=${pod}
kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found --wait=false >/dev/null 2>&1 || true
sleep 2
kubectl --context \$CTX -n \$NS run \$POD --restart=Never --image=python:3.11-alpine --command -- \\
  sh -lc 'set -e
apk add --no-cache git ca-certificates
pip install --no-cache-dir requests
rm -rf /tmp/go-micro
git clone --depth 1 https://github.com/minhtri1612/go-micro.git /tmp/go-micro
cd /tmp/go-micro
find tests -name "*.py" -exec sed -i "s/dev.go-micro.local/${host}/g" {} +
CANARY_HEADER=true python3 tests/${suite}/run.py ${svc} ${ip}
'
for i in \$(seq 1 90); do
  PH=\$(kubectl --context \$CTX -n \$NS get pod \$POD -o jsonpath="{.status.phase}" 2>/dev/null || echo Pending)
  echo "pod \$POD phase=\$PH"
  case "\$PH" in
    Succeeded)
      kubectl --context \$CTX -n \$NS logs \$POD
      kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found >/dev/null
      exit 0
      ;;
    Failed)
      kubectl --context \$CTX -n \$NS logs \$POD || true
      kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found >/dev/null
      exit 1
      ;;
  esac
  sleep 2
done
kubectl --context \$CTX -n \$NS logs \$POD || true
kubectl --context \$CTX -n \$NS describe pod \$POD || true
kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found >/dev/null
exit 1
""", 420)
}

def runK6Test() {
  def svc = env.CI_ENV_KEY
  def ip = env.CI_BACKEND_IP
  def ctx = env.CI_KUBE_CONTEXT ?: 'kind-dev'
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  def pod = "ci-k6-${env.BUILD_NUMBER}".toLowerCase().replaceAll('[^a-z0-9-]', '').take(50)
  def host = env.CI_INGRESS_HOST ?: 'dev.go-micro.local'
  echo "Parallel k6 → pod/${pod} ${svc} ${ip} Host=${host}"
  ssmOnKind("""set -e
export KUBECONFIG=/root/.kube/config
export PATH=/usr/local/bin:/usr/bin:\$PATH
CTX=${ctx}
NS=${ns}
POD=${pod}
kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found --wait=false >/dev/null 2>&1 || true
sleep 2
kubectl --context \$CTX -n \$NS run \$POD --restart=Never --image=grafana/k6:0.49.0 --command -- \\
  sh -lc 'cat >/tmp/k6.js <<EOF
import http from "k6/http";
import { check, sleep } from "k6";
const vus = __ENV.VUS || 10;
const duration = __ENV.DURATION || "30s";
const errorRate = __ENV.ERROR_RATE || "0.1";
const service = __ENV.SERVICE_NAME || "order";
const target = __ENV.TARGET_URL || "localhost";
export const options = { vus: vus, duration: duration, thresholds: { http_req_failed: ["rate<" + errorRate], http_req_duration: ["p(95)<2000"] } };
function getPrefix(s) { return ({ product:"/api/v1/products", order:"/api/v1/orders", inventory:"/api/v1/inventory", noti:"/api/v1/notifications", payment:"/api/v1/payments", client:"/" }[s] || "/api/v1/products"); }
function probePath(s) { if (s === "client") return "/"; if (s === "order") return "/orders"; return "/health"; }
export default function () { const res = http.get("http://" + target + getPrefix(service) + probePath(service), { headers: { Host: "${host}", "X-Canary": "true" } }); check(res, { "status is 200": (r) => r.status === 200 }); sleep(0.3); }
EOF
TARGET_URL=${ip} SERVICE_NAME=${svc} VUS=10 DURATION=30s ERROR_RATE=0.1 k6 run /tmp/k6.js
'
for i in \$(seq 1 90); do
  PH=\$(kubectl --context \$CTX -n \$NS get pod \$POD -o jsonpath="{.status.phase}" 2>/dev/null || echo Pending)
  echo "pod \$POD phase=\$PH"
  case "\$PH" in
    Succeeded)
      kubectl --context \$CTX -n \$NS logs \$POD
      kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found >/dev/null
      exit 0
      ;;
    Failed)
      kubectl --context \$CTX -n \$NS logs \$POD || true
      kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found >/dev/null
      exit 1
      ;;
  esac
  sleep 2
done
kubectl --context \$CTX -n \$NS logs \$POD || true
kubectl --context \$CTX -n \$NS describe pod \$POD || true
kubectl --context \$CTX -n \$NS delete pod \$POD --ignore-not-found >/dev/null
exit 1
""", 420)
}
