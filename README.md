# go-micro-ci

Jenkins **ở ngoài cluster**. Shared library này là phần CI của DevOps.

CD không nằm đây. Argo CD trên máy Kind đọc `go-micro-gitops` rồi sync.

## Ai sở hữu gì

| | Dev | DevOps |
|---|---|---|
| Repo | `go-micro-product`, `order`, … | `go-micro-pipeline-lib`, `go-micro-gitops`, Jenkins server |
| File | `Jenkinsfile` mỏng (tên service + image) | `vars/ciGoMicroService.groovy`, Job DSL, credentials |
| Việc khi code đổi | `git push` repo của mình | Không đụng. Job service đó tự chạy |
| Không làm | Cài Jenkins, SSH Kind, sửa Argo | Viết business logic trong service |

Một Jenkins controller. Mỗi service một job. Không phải mỗi dev một server Jenkins.

```
dev  git push  go-micro-product
        → Jenkins job services/product
        → build/push image
        → bump go-micro-gitops/env/dev.yaml
        → Argo CD (máy Kind) sync
```

## Jenkinsfile (Dev)

```groovy
@Library('go-micro-ci') _

ciGoMicroService([
  service   : 'product',
  imageRepo : 'minhtri1612/product-service',
])
```

Mặc định: GitOps `env/dev.yaml`, branch `main`. Dev không viết docker/gitops trong Jenkinsfile.

## Stages (mọi job giống nhau)

1. **Identify** — service, git SHA, image tag `{image}-{sha7}`
2. **Build & Push** — `docker build` / `docker push` (credential `dockerhub-credentials`)
3. **Bump GitOps** — clone `go-micro-gitops`, ghi tag, push `[skip ci]` (credential `github-go-micro-pat`)

Jenkins **không** `kubectl apply`. Cluster là việc của Argo.

## Cài library trên Jenkins

Manage Jenkins → System → Global Pipeline Libraries:

| Field | Value |
|--------|--------|
| Name | `go-micro-ci` |
| Default version | `main` |
| Retrieval method | Modern SCM → Git |
| Project repo | `https://github.com/minhtri1612/go-micro-pipeline-lib.git` |

Hoặc để JCasC trong `go-micro-infra/jenkins/` tạo sẵn.

Credentials trên controller: `dockerhub-credentials`, `github-go-micro-pat`.

## `vars/`

| File | Việc |
|------|------|
| `ciGoMicroService.groovy` | Entry: build/push + bump GitOps |
| `libBuild.groovy` | Build/push + ghi tag |
| `libPrecheck.groovy` | Scope, detect change |
| `libTests.groovy` | Smoke / k6 / prepare |
| `libRollback.groovy` | Promote / abort / rollback |

