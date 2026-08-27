# go-micro-pipeline-lib

Jenkins **Shared Library** for go-micro multi-repo + **VPS Jenkins**.

## Configure on VPS Jenkins

Manage Jenkins → System → Global Pipeline Libraries:

| Field | Value |
|-------|--------|
| Name | `go-micro-ci` |
| Default version | `main` |
| Retrieval method | Modern SCM → Git |
| Project repo | `https://github.com/minhtri1612/go-micro-pipeline-lib.git` |
| Library path | *(empty)* |

Credentials on controller: `dockerhub-credentials`, `github-go-micro-pat`, kubeconfig for `kind-dev` (etc.).

## Usage (each service repo)

```groovy
@Library('go-micro-ci') _

ciGoMicroService([
  service   : 'payment',
  imageRepo : 'minhtri1612/payment-service',
  gitopsRepo: 'https://github.com/minhtri1612/go-micro-gitops.git',
  envFile   : 'env/dev.yaml'
])
```

`ciGoMicroService` builds/pushes the **service** image, then clones **gitops** and bumps `env/*.yaml`. Argo syncs the cluster — Jenkins is not managed by Argo.
