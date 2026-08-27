def call(Map cfg = [:]) {
  def service = cfg.service ?: error('ciGoMicroService: missing service')
  def imageRepo = cfg.imageRepo ?: error('ciGoMicroService: missing imageRepo')
  def gitopsRepo = cfg.gitopsRepo ?: 'https://github.com/minhtri1612/go-micro-gitops.git'
  def envFile = cfg.envFile ?: 'env/dev.yaml'

  echo "ciGoMicroService service=${service} imageRepo=${imageRepo} gitops=${gitopsRepo} envFile=${envFile}"
  echo "TODO: wire libPrecheck/libBuild/libRollback/libTests for multi-repo + VPS Jenkins"
  echo "Adapt git push target to gitopsRepo (not the service app repo)."
}