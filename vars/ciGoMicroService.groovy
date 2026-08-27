def call(Map cfg = [:]) {
  // Thin entry: multi-repo service job + VPS Jenkins → bump go-micro-gitops.
  def service = (cfg.service ?: error('ciGoMicroService: missing service')).toString().trim()
  def imageRepo = (cfg.imageRepo ?: error('ciGoMicroService: missing imageRepo')).toString().trim()
  def gitopsRepo = (cfg.gitopsRepo ?: 'https://github.com/minhtri1612/go-micro-gitops.git').toString().trim()
  def envFile = (cfg.envFile ?: 'env/dev.yaml').toString().trim()
  def gitBranch = (cfg.gitBranch ?: 'main').toString().trim()

  def envKey = (service in ['notification', 'noti']) ? 'noti' : service
  def gitSha = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
  def imageName = imageRepo.tokenize('/')[-1]
  def fullTag = "${imageName}-${gitSha}"

  echo "=== ciGoMicroService service=${service} envKey=${envKey} tag=${fullTag} ==="

  withCredentials([usernamePassword(
    credentialsId: 'dockerhub-credentials',
    usernameVariable: 'DOCKER_USER',
    passwordVariable: 'DOCKER_PASS'
  )]) {
    sh """
      set -e
      echo "\$DOCKER_PASS" | docker login -u "\$DOCKER_USER" --password-stdin
      docker build -t '${imageRepo}:${fullTag}' .
      docker push '${imageRepo}:${fullTag}'
    """
  }

  def gitopsHttps = gitopsRepo.replace('https://', '')
  dir('gitops-checkout') {
    deleteDir()
    withCredentials([usernamePassword(
      credentialsId: 'github-go-micro-pat',
      usernameVariable: 'GH_USER',
      passwordVariable: 'GH_TOKEN'
    )]) {
      sh """
        set -e
        git clone --depth 1 --branch '${gitBranch}' \
          "https://x-access-token:\${GH_TOKEN}@${gitopsHttps}" .
      """
      // Patch envKey.image.tag in env YAML (indent-aware, first block match)
      def envPath = envFile
      def yaml = readFile(envPath)
      def lines = yaml.readLines()
      def out = new StringBuilder()
      def inBlock = false
      def patched = false
      lines.each { line ->
        if (line ==~ /^${java.util.regex.Pattern.quote(envKey)}:\\s*/) {
          inBlock = true
          out.append(line).append('\n')
          return
        }
        if (inBlock && line ==~ /^[A-Za-z0-9_-]+:.*/) {
          inBlock = false
        }
        if (inBlock && !patched && line ==~ /^\\s+tag:\\s*.*/) {
          def indent = (line =~ /^(\\s+)/)[0][1]
          out.append("${indent}tag: \"${fullTag}\"\n")
          patched = true
          return
        }
        out.append(line).append('\n')
      }
      if (!patched) {
        error("ciGoMicroService: cannot find ${envKey}.image.tag in ${envPath}")
      }
      writeFile file: envPath, text: out.toString()

      sh """
        set -e
        git config user.email 'jenkins@go-micro.local'
        git config user.name 'jenkins-ci'
        git add '${envFile}'
        if git diff --cached --quiet; then
          echo 'Nothing to commit in gitops'
        else
          git commit -m "ci: bump ${envKey} in ${envFile} [skip ci] #\${BUILD_NUMBER}"
          git push origin "HEAD:${gitBranch}"
        fi
      """
    }
  }

  echo "Image ${imageRepo}:${fullTag} pushed; gitops ${envFile} bumped. Argo will sync."
  echo "TODO next: wait rollout + libTests + promote gate (libRollback) on VPS kubeconfig."
}
