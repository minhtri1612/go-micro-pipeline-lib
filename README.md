# go-micro-ci

Jenkins **ở ngoài cluster**. Shared library này là phần CI của DevOps.

CD không nằm đây. Argo CD trên RKE2 management đọc `go-micro-gitops` rồi sync.

## Ai sở hữu gì

| | Dev | DevOps |
|---|---|---|
| Repo | `go-micro-product`, `order`, … | `go-micro-pipeline-lib`, `go-micro-gitops`, Jenkins server |
| File | `Jenkinsfile` một dòng (`ciGoMicroService('product')`) | allowlist image/GitOps trong library, Job DSL, credentials |
| Việc khi code đổi | `git push` repo của mình | Không đụng. Job service đó tự chạy |
| Không làm | Cài Jenkins, SSH cluster, sửa Argo | Viết business logic trong service |

Một Jenkins controller. Mỗi service một job. Không phải mỗi dev một server Jenkins.

```
dev  git push  go-micro-product
        → Jenkins job services/product
        → test
        → build/push image
        → bump go-micro-gitops/env/dev/product.yaml
        → Argo CD (RKE2 management) sync
```

## Jenkinsfile (Dev)

```groovy
@Library('go-micro-ci@main') _

ciGoMicroService('product')
```

Image repo, GitOps repo, branch, và file env nằm **allowlist trong library**. Truyền `imageRepo` / `gitopsRepo` / `gitBranch` bị bỏ qua.

## Stages (mọi job giống nhau)

1. **Identify** — service, git SHA, image tag `{image}-{sha7}`
2. **Test** — Go: `go test` (bỏ integration). Client: `npm ci && npm run lint`. Fail thì dừng.
3. **Build & Push** — `docker build` / `docker push` (credential `dockerhub-credentials`)
4. **Bump GitOps** — clone `go-micro-gitops`, ghi tag vào `env/<env>/<service>.yaml`, push `[skip ci]` (credential `github-go-micro-pat`)
5. **Wait canary / k6 / Rollout** — chỉ trên `main`

Jenkins **không** `kubectl apply`. Cluster là việc của Argo.

`TARGET_ENV=prod` (DevOps, job `main`): không rebuild; mở PR copy tag `env/dev/<service>.yaml` → `env/prod/<service>.yaml`.

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
| `ciGoMicroService.groovy` | Entry: allowlist + test + build/push + bump GitOps |
| `libBuild.groovy` | LEGACY monorepo |
| `libPrecheck.groovy` | LEGACY monorepo |
| `libTests.groovy` | LEGACY smoke / k6 helpers |
| `libRollback.groovy` | LEGACY promote / abort |
