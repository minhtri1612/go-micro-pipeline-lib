# go-micro-ci

Jenkins **ở ngoài cluster**. Shared library này là phần CI của DevOps.

CD không nằm đây. Argo CD trên RKE2 management đọc `go-micro-gitops` rồi sync.

## Ai sở hữu gì

| | Dev | DevOps |
|---|---|---|
| Repo | `go-micro-product`, `order`, … | `go-micro-pipeline-lib`, `go-micro-gitops`, Jenkins server |
| File | `Jenkinsfile` một dòng (`ciGoMicroService('product')`) | allowlist + Job DSL `EXPECTED_SERVICE` + folder `release/` |
| Việc khi code đổi | `git push` repo của mình | Không đụng. Job `services/<name>` test, `release/<name>` deploy |
| Không làm | Cài Jenkins, SSH cluster, sửa Argo | Viết business logic trong service |

Một Jenkins controller. Mỗi service: job test (`services/*`) + job release DevOps-owned. Không phải mỗi dev một server Jenkins.

```
dev  git push  go-micro-product
        → Jenkins job services/product   (Jenkinsfile trong repo — chỉ Test)
        → Jenkins job release/product    (Jenkinsfile trong go-micro-infra)
        → checkout allowlist repo
        → test
        → build/push image
        → bump go-micro-gitops/env/dev/product.yaml
        → Argo CD (RKE2 management) sync
```

Library fail nếu `ciGoMicroService('payment')` chạy trên job `services/product/...`.

## Jenkinsfile (Dev)

```groovy
@Library('go-micro-ci') _

ciGoMicroService('product')
```

Không pin `@main`. Version library do CasC (`defaultVersion` tag, `allowVersionOverride: false`).

Image repo, GitOps repo, branch, và file env nằm **allowlist trong library**. Truyền `imageRepo` / `gitopsRepo` / `gitBranch` bị bỏ qua.

Job phải là `services/<service>/...` hoặc `release/<service>`. `release/` bắt buộc `EXPECTED_SERVICE` từ Job DSL.

## Stages

**`services/*`** (Jenkinsfile do dev sửa được)

1. **Identify** — khớp `JOB_NAME` với service
2. **Test** — Go: `go test` + `go vet` (bỏ integration). Client: `npm ci && npm run lint && npm run build` (+ `npm test` nếu có script). Fail thì dừng.
3. **Handoff** — chỉ `main`: trigger `release/<service>`

Không `withCredentials` trên job này.

**`release/*`** (Jenkinsfile trong `go-micro-infra`, không lấy từ service repo)

1. **Identify** — checkout `gitRepo` allowlist (`main`)
2. **Test** — như trên
3. **Build & Push** — credential folder `dockerhub-credentials`
4. **Bump GitOps** — credential folder `github-gitops-write`
5. **Wait canary / k6 / Rollout**

Jenkins **không** `kubectl apply`. Cluster là việc của Argo.

`TARGET_ENV=prod` (DevOps, job `release/<service>`): không rebuild; mở PR copy tag `env/dev/<service>.yaml` → `env/prod/<service>.yaml`.

## Cài library trên Jenkins

Manage Jenkins → System → Global Pipeline Libraries:

| Field | Value |
|--------|--------|
| Name | `go-micro-ci` |
| Default version | `v1.1.0` (tag, không `main`) |
| Allow version override | false |
| Retrieval method | Modern SCM → Git |
| Project repo | `https://github.com/minhtri1612/go-micro-pipeline-lib.git` |

Hoặc để JCasC trong `go-micro-infra/jenkins/` tạo sẵn.

Clone: `github-go-micro-pat` (GLOBAL, nên là PAT read-only). Push image / GitOps: folder `release/` only.

## `vars/`

| File | Việc |
|------|------|
| `ciGoMicroService.groovy` | Entry: job-bind + test + release-only build/push + bump GitOps |
| `libBuild.groovy` | LEGACY monorepo |
| `libPrecheck.groovy` | LEGACY monorepo |
| `libTests.groovy` | LEGACY smoke / k6 helpers |
| `libRollback.groovy` | LEGACY promote / abort |
