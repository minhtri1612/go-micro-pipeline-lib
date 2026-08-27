// Build + push Docker images, ghi env/<env>.yaml, push Git.
// Tag = semver (từ bump-image-tag.sh) + git short SHA → cho rollback truy ngược commit chính xác.

def isSkipImageBuild() {
  return (env.SKIP_IMAGE_BUILD ?: '').toString() == 'true'
}

def resolveBuildServicesList() {
  if (env.DETECTED_SERVICES?.trim()) {
    return env.DETECTED_SERVICES.trim()
  }
  def allSvcs = 'product inventory order payment noti client'
  if (params.BUILD_SERVICES == 'all') {
    return allSvcs
  }
  if (params.BUILD_SERVICES == 'auto') {
    return sh(script: 'bash scripts/ci/detect-changed-services.sh', returnStdout: true).trim()
  }
  return params.BUILD_SERVICES?.trim() ?: ''
}

def saveEnvFileSnapshot(String envFile) {
  env.ENV_FILE_SNAPSHOT_PATH = envFile
  env.ENV_FILE_SNAPSHOT = sh(script: "cat '${envFile}'", returnStdout: true)
  echo "Snapshot saved: ${envFile} (${env.ENV_FILE_SNAPSHOT.length()} bytes)"
}

def runImageBuildSteps() {
  if (isSkipImageBuild()) {
    echo 'runImageBuildSteps: skipped.'
    return
  }
  def envFile = "env/${params.TARGET_ENV}.yaml"
  saveEnvFileSnapshot(envFile)
  def svcs = resolveBuildServicesList()
  if (!svcs?.trim()) {
    echo "Không có service để build (BUILD_SERVICES=${params.BUILD_SERVICES}) — bỏ qua, không fail."
    env.SKIP_IMAGE_BUILD = 'true'
    return
  }
  // Lấy git SHA lúc bắt đầu build (= commit code của developer, TRƯỚC commit ci: bump).
  def gitSha = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
  echo "Build SHA: ${gitSha} (commit code, trước ci-bump)"
  withCredentials([usernamePassword(
    credentialsId: 'dockerhub-credentials',
    usernameVariable: 'DOCKER_USER',
    passwordVariable: 'DOCKER_PASS'
  )]) {
    sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'
    svcs.split(/\s+/).each { svc ->
      // bump-image-tag.sh chỉ trả về phần semver (vd: payment-service-v1.0.5).
      // SHA được ghép ở đây để image tag = semver + commit code thực tế.
      def semverTag = sh(script: "bash scripts/ci/bump-image-tag.sh --compute-only '${envFile}' '${svc}'", returnStdout: true).trim()
      def tag = "${semverTag}-${gitSha}"
      echo "${svc} → build tag ${tag} (semver+SHA; env ghi sau khi image sẵn sàng trên Hub)"
      def onHub = sh(script: "bash scripts/ci/image-exists-on-hub.sh '${tag}'", returnStatus: true) == 0
      if (onHub) {
        echo "${svc}: ${tag} đã có trên Hub — skip docker build/push"
      } else {
        sh "bash scripts/ci/docker-build-push.sh '${svc}' '${tag}'"
        def onHubAfter = sh(script: "bash scripts/ci/image-exists-on-hub.sh '${tag}'", returnStatus: true) == 0
        if (!onHubAfter) {
          error("${svc}: push Hub thất bại — không ghi tag ${tag} vào ${envFile}")
        }
      }
      sh "bash scripts/ci/write-service-tag.sh '${envFile}' '${svc}' '${tag}'"
      echo "${svc}: env/${params.TARGET_ENV}.yaml ← ${tag} (đúng tag Hub)"
    }
  }
}

/**
 * Trả về Map<service, expected-tag> cho các service vừa build (loại trừ `client`).
 * Dùng để stage 'Wait Argo Sync' biết tag nào cần đợi ArgoCD apply vào rollout.
 * Đọc thẳng từ env/<env>.yaml — runImageBuildSteps đã ghi tag chính xác (semver+sha) sau khi image lên Hub.
 */
def getBuiltRolloutServiceTags() {
  def envFile = "env/${params.TARGET_ENV}.yaml"
  def src = env.DETECTED_SERVICES?.trim() ?: resolveBuildServicesList()
  def result = [:]
  if (!src?.trim()) {
    return result
  }
  src.tokenize().findAll { it && it != 'client' }.each { svc ->
    def tag = sh(
      script: "bash scripts/ci/read-env-tag.sh '${envFile}' '${svc}'",
      returnStdout: true
    ).trim()
    if (tag) {
      result[svc] = tag
    }
  }
  return result
}

def runCiGitPushSteps() {
  def envFile = "env/${params.TARGET_ENV}.yaml"
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
        echo 'Nothing to commit'
        exit 0
      fi
      git commit -m 'ci: bump tags in ${envFile} [skip ci] #${env.BUILD_NUMBER}'
      git push "https://x-access-token:\${GH_TOKEN}@github.com/minhtri1612/go-micro.git" "HEAD:${params.GIT_BRANCH}"
    """
  }
}

