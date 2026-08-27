// Precheck + pipeline scope logic.
// Phân biệt commit của user vs CI, detect changes, set SKIP_* flags để các stage sau biết bỏ qua hay chạy.
// Được load 1 lần ở stage 'Init' trong Jenkinsfile, dùng qua biến `libPrecheck.xxx()`.

def isUserTriggeredBuild() {
  return currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')?.size() > 0
}

def isSkipQualityGates() {
  return (env.SKIP_QUALITY_GATES ?: '').toString() == 'true'
}

def pipelineNeedsCheckout() {
  return params.PIPELINE_SCOPE in ['auto', 'build-only', 'build-and-full', 'full', 'rollback']
}

def pipelineNeedsPrecheck() {
  return params.PIPELINE_SCOPE in ['auto', 'build-only', 'build-and-full']
}

def pipelineNeedsImageBuild() {
  return params.PIPELINE_SCOPE in ['auto', 'build-only', 'build-and-full']
}

def pipelineNeedsQualityGates() {
  def s = params.PIPELINE_SCOPE
  if (s == 'build-only') {
    return false
  }
  if (s in ['dependency-only', 'business-only', 'route-smoke-only', 'load-only', 'full', 'build-and-full']) {
    return true
  }
  if (s == 'auto') {
    return !isSkipQualityGates()
  }
  return false
}

def pipelineNeedsParallelTests() {
  def s = params.PIPELINE_SCOPE
  if (s in ['dependency-only', 'business-only', 'route-smoke-only', 'load-only']) {
    return true
  }
  return pipelineNeedsQualityGates() && (s in ['full', 'build-and-full', 'auto'])
}

def pipelineNeedsDependencyCheck() {
  def s = params.PIPELINE_SCOPE
  return s == 'dependency-only' || (pipelineNeedsQualityGates() && s in ['full', 'build-and-full', 'auto'])
}

def pipelineNeedsBusinessSmoke() {
  def s = params.PIPELINE_SCOPE
  return s == 'business-only' || (pipelineNeedsQualityGates() && s in ['full', 'build-and-full', 'auto'])
}

def pipelineNeedsRouteSmoke() {
  def s = params.PIPELINE_SCOPE
  return s == 'route-smoke-only' || (pipelineNeedsQualityGates() && s in ['full', 'build-and-full', 'auto'])
}

def pipelineNeedsK6Load() {
  def s = params.PIPELINE_SCOPE
  return s == 'load-only' || (pipelineNeedsQualityGates() && s in ['full', 'build-and-full', 'auto'])
}

def handleEnvOnlyDeploy(String envFile) {
  def changed = sh(script: "bash scripts/ci/detect-env-changed-services.sh '${envFile}'", returnStdout: true).trim()
  if (!changed) {
    changed = sh(script: "bash scripts/ci/list-env-app-services.sh '${envFile}'", returnStdout: true).trim()
    echo "env/: kiểm tra Hub cho mọi app service trong ${envFile}"
  }
  echo "env deploy targets: ${changed.replaceAll('\\n', ' ')}"
  // Không nhét list service vào 1 dòng sh (newline → shell chạy inventory/noti như lệnh lẻ).
  def verifyRc = sh(
    script: "bash scripts/ci/verify-env-tags-on-hub.sh '${envFile}'",
    returnStatus: true
  )
  if (verifyRc != 0) {
    error('Tag trong env/ chưa có trên Docker Hub — build image trước hoặc sửa tag cho đúng Hub.')
  }
  env.SKIP_IMAGE_BUILD = 'true'
  env.SKIP_QUALITY_GATES = 'false'
  applyAutoTestTargets(changed)
  currentBuild.description = "env/ tags on Hub → test+promote [${changed}]"
  echo 'Skip Build + Push Git (tag đã trên Hub). Đảm bảo Argo đã sync Git env/ → chạy test + promote.'
}

def applyAutoTestTargets(String detected) {
  def rolloutList = detected.tokenize().findAll { it != 'client' }
  if (rolloutList.size() == 1) {
    env.EFFECTIVE_DEPENDENCY_SERVICES = rolloutList[0]
    env.EFFECTIVE_BUSINESS_SERVICES = rolloutList[0]
    echo "auto: test + promote target → ${rolloutList[0]}"
  } else if (rolloutList.size() > 1) {
    echo "auto: build [${detected}] — test dùng DEPENDENCY/BUSINESS trên Jenkins (mặc định all)"
  }
}

def precheckPipeline() {
  env.SKIP_IMAGE_BUILD = 'false'
  env.SKIP_QUALITY_GATES = 'false'
  env.DETECTED_SERVICES = ''
  if (params.PIPELINE_SCOPE == 'build-only') {
    echo 'Gợi ý: commit chỉ Jenkinsfile/env → dùng PIPELINE_SCOPE=auto hoặc bật DEPLOY_EXISTING_ENV_TAGS.'
  }
  if (params.DEPLOY_EXISTING_ENV_TAGS == true || params.DEPLOY_EXISTING_ENV_TAGS == 'true') {
    handleEnvOnlyDeploy("env/${params.TARGET_ENV}.yaml")
    return
  }
  def msg = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
  if (msg ==~ /(?i)^ci:\s*(bump|rollback)\b.*/ || msg.contains('[skip ci]')) {
    if (isUserTriggeredBuild()) {
      echo "Build tay trên commit CI — không build Docker lại; test+promote tag trong env/${params.TARGET_ENV}.yaml"
      echo "  HEAD: '${msg}'"
      handleEnvOnlyDeploy("env/${params.TARGET_ENV}.yaml")
      return
    }
    env.SKIP_IMAGE_BUILD = 'true'
    env.SKIP_QUALITY_GATES = 'true'
    currentBuild.description = 'Skipped: ci [skip ci] commit (tránh vòng lặp webhook)'
    echo "SKIP all (SCM/auto trên commit CI): '${msg}'"
    echo 'Muốn chạy test/promote: Build Now (tay) hoặc bật DEPLOY_EXISTING_ENV_TAGS / PIPELINE_SCOPE=full.'
    return
  }

  if (params.PIPELINE_SCOPE == 'auto') {
    def mode = sh(script: 'bash scripts/ci/detect-commit-mode.sh', returnStdout: true).trim()
    def detected = sh(script: 'bash scripts/ci/detect-changed-services.sh', returnStdout: true).trim()
    echo "auto: mode=${mode}, services='${detected}'"
    if (mode == 'service' && detected) {
      env.SKIP_IMAGE_BUILD = 'false'
      env.SKIP_QUALITY_GATES = 'false'
      env.DETECTED_SERVICES = detected
      applyAutoTestTargets(detected)
      currentBuild.description = "auto: build+test [${detected}]"
      return
    }
    if (mode == 'env-only') {
      handleEnvOnlyDeploy("env/${params.TARGET_ENV}.yaml")
      return
    }
    // Chỉ Jenkinsfile/scripts — skip build, VẪN chạy test+promote với tag trong env/ (không vứt Parallel tests).
    echo 'auto: mode=none — commit không đổi *-service/ hay env/ trong diff; dùng tag hiện tại env/ + verify Hub.'
    sh 'git show -1 --name-only --pretty=format:"  %h %s"'
    handleEnvOnlyDeploy("env/${params.TARGET_ENV}.yaml")
    return
  }

  precheckLegacyBuildScope()
}

def precheckLegacyBuildScope() {
  if (params.BUILD_SERVICES == 'auto') {
    def detected = sh(script: 'bash scripts/ci/detect-changed-services.sh', returnStdout: true).trim()
    if (!detected) {
      env.SKIP_IMAGE_BUILD = 'true'
      env.SKIP_QUALITY_GATES = 'true'
      currentBuild.description = 'Skipped: không có *-service/ trong commit'
      echo 'SKIP build: BUILD_SERVICES=auto, không có service đổi'
      sh 'git show -1 --name-only --pretty=format:"  %h %s"'
      return
    }
    env.DETECTED_SERVICES = detected
    echo "build scope → ${detected}"
  }
  env.SKIP_IMAGE_BUILD = 'false'
  env.SKIP_QUALITY_GATES = (params.PIPELINE_SCOPE == 'build-only') ? 'true' : 'false'
}

