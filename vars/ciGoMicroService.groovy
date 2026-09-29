def call(Map cfg = [:]) {
  // DevOps-owned CI. Dev only passes service identity from Jenkinsfile.
  def service = (cfg.service ?: error('ciGoMicroService: missing service')).toString().trim()
  def imageRepo = (cfg.imageRepo ?: error('ciGoMicroService: missing imageRepo')).toString().trim()
  def gitopsRepo = (cfg.gitopsRepo ?: 'https://github.com/minhtri1612/go-micro-gitops.git').toString().trim()
  def envFile = (cfg.envFile ?: 'env/dev.yaml').toString().trim()
  def gitBranch = (cfg.gitBranch ?: 'main').toString().trim()
  def envKey = (service in ['notification', 'noti']) ? 'noti' : service

  pipeline {
    agent none
    options {
      timestamps()
      disableConcurrentBuilds()
    }
    stages {
      stage('Identify') {
        agent any
        steps {
          script {
            env.CI_SERVICE = service
            env.CI_IMAGE_REPO = imageRepo
            env.CI_ENV_KEY = envKey
            env.CI_ENV_FILE = envFile
            env.CI_GITOPS_REPO = gitopsRepo
            env.CI_GITOPS_BRANCH = gitBranch
            env.CI_GIT_SHA = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
            env.CI_IMAGE_NAME = imageRepo.tokenize('/')[-1]
            env.CI_FULL_TAG = "${env.CI_IMAGE_NAME}-${env.CI_GIT_SHA}"
            env.CI_BUMP_GITOPS = isGitopsBumpBranch() ? '1' : '0'
            env.CI_SKIP_ROLLOUT_GATE = '0'
            env.CI_KUBE_CONTEXT = env.CI_KUBE_CONTEXT ?: 'kind-dev'
            env.CI_ROLLOUT_NS = env.CI_ROLLOUT_NS ?: 'microservices-dev'
            echo "service=${service}  gitopsKey=${envKey}  tag=${env.CI_FULL_TAG}"
            echo "branch=${env.BRANCH_NAME} changeId=${env.CHANGE_ID} bumpGitops=${env.CI_BUMP_GITOPS}"
            echo "Dev: repo + Jenkinsfile. DevOps: this library + GitOps. CD: Argo CD. Promote/Abort: Jenkins button on main."
          }
        }
      }
      stage('Build & Push') {
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
                def yaml = readFile(env.CI_ENV_FILE)
                def patched = patchEnvTag(yaml, env.CI_ENV_KEY, env.CI_FULL_TAG)
                if (!patched.ok) {
                  error("ciGoMicroService: cannot find ${env.CI_ENV_KEY}.image.tag in ${env.CI_ENV_FILE}")
                }
                writeFile file: env.CI_ENV_FILE, text: patched.text
                sh """
                  set -e
                  git config user.email 'jenkins@go-micro.local'
                  git config user.name 'jenkins-ci'
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
      stage('Wait canary') {
        when {
          environment name: 'CI_BUMP_GITOPS', value: '1'
        }
        agent any
        steps {
          script {
            waitCanaryPaused()
          }
        }
      }
      stage('Rollout') {
        when {
          allOf {
            environment name: 'CI_BUMP_GITOPS', value: '1'
            not { environment name: 'CI_SKIP_ROLLOUT_GATE', value: '1' }
          }
        }
        options {
          timeout(time: 30, unit: 'MINUTES')
        }
        input {
          message 'Canary đang pause. Promote = tiếp 50→100. Abort = hủy canary.'
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

def kindKubectl(String args) {
  def ctx = env.CI_KUBE_CONTEXT ?: 'kind-dev'
  def ns = env.CI_ROLLOUT_NS ?: 'microservices-dev'
  return ssmOnKind("""set -e
export KUBECONFIG=/root/.kube/config
export PATH=/usr/local/bin:/usr/bin:\$PATH
kubectl --context ${ctx} -n ${ns} ${args}
""")
}

def ssmOnKind(String inner) {
  withEnv([
    'KIND_INNER_CMD=' + inner,
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
        --timeout-seconds 90 \
        --parameters "$PAYLOAD" \
        --query Command.CommandId --output text)
      ST=Pending
      i=0
      while [ "$i" -lt 45 ]; do
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
      echo 'Canary paused. Use the Jenkins Rollout button (Promote / Abort).'
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
