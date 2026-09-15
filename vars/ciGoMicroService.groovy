def call(Map cfg = [:]) {
  // DevOps-owned CI. Dev only passes service identity from Jenkinsfile.
  def service = (cfg.service ?: error('ciGoMicroService: missing service')).toString().trim()
  def imageRepo = (cfg.imageRepo ?: error('ciGoMicroService: missing imageRepo')).toString().trim()
  def gitopsRepo = (cfg.gitopsRepo ?: 'https://github.com/minhtri1612/go-micro-gitops.git').toString().trim()
  def envFile = (cfg.envFile ?: 'env/dev.yaml').toString().trim()
  def gitBranch = (cfg.gitBranch ?: 'main').toString().trim()
  def envKey = (service in ['notification', 'noti']) ? 'noti' : service

  pipeline {
    agent any
    options {
      timestamps()
      disableConcurrentBuilds()
    }
    stages {
      stage('Identify') {
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
            echo "service=${service}  gitopsKey=${envKey}  tag=${env.CI_FULL_TAG}"
            echo "Dev: repo + Jenkinsfile. DevOps: this library + GitOps. CD: Argo CD (not Jenkins)."
          }
        }
      }
      stage('Build & Push') {
        steps {
          withCredentials([usernamePassword(
            credentialsId: 'dockerhub-credentials',
            usernameVariable: 'DOCKER_USER',
            passwordVariable: 'DOCKER_PASS'
          )]) {
            sh '''
              set -e
              echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
              docker build -t "${CI_IMAGE_REPO}:${CI_FULL_TAG}" .
              docker push "${CI_IMAGE_REPO}:${CI_FULL_TAG}"
            '''
          }
        }
      }
      stage('Bump GitOps') {
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
    }
    post {
      success {
        echo "CI done: ${env.CI_IMAGE_REPO}:${env.CI_FULL_TAG} → ${env.CI_ENV_FILE}. Argo CD on the Kind host syncs CD. Jenkins does not deploy."
      }
    }
  }
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
