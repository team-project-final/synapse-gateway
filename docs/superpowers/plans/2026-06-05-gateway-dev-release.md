# Gateway dev 배포 파이프라인 복구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub OIDC 신뢰 구축 + ECR/SM 리소스 시드로 gateway dev 배포를 복구하고 이슈 #4를 해소한다.

**Architecture:** AWS에 GitHub Actions OIDC 프로바이더와 최소 권한 배포 롤을 신규 생성하고, `AWS_ROLE_ARN` 시크릿을 등록해 기존 reusable 워크플로우(`synapse-shared/deploy-service.yml`)가 동작하게 한다. gitops 계약이 기대하는 ECR 리포(`synapse/gateway`)와 SM 시크릿(`synapse/dev/gateway/redis-password`)을 시드한 뒤 Deploy를 재실행해 검증한다. 앱 코드 변경은 없다.

**Tech Stack:** AWS CLI (IAM/ECR/SecretsManager), gh CLI, GitHub Actions, PowerShell

**Spec:** `docs/superpowers/specs/2026-06-05-gateway-dev-release-design.md`

**전제:** 로컬 AWS 자격 = `synapse-admin`(계정 963773969059), 리전 `ap-northeast-2`. 작업 디렉터리 = `C:\workspace\team-project-final\synapse-gateway`.

---

### Task 1: GitHub OIDC 프로바이더 생성

**Files:** 없음 (AWS 리소스만)

- [ ] **Step 1: 프로바이더 부재 재확인 (멱등성 가드)**

Run:
```powershell
aws iam list-open-id-connect-providers --output json
```
Expected: `token.actions.githubusercontent.com`이 목록에 **없음** (EKS 프로바이더 2개만). 이미 있으면 Step 2를 건너뛰고 기존 ARN을 사용.

- [ ] **Step 2: 프로바이더 생성**

Run:
```powershell
aws iam create-open-id-connect-provider `
  --url https://token.actions.githubusercontent.com `
  --client-id-list sts.amazonaws.com `
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```
Expected: `{"OpenIDConnectProviderArn": "arn:aws:iam::963773969059:oidc-provider/token.actions.githubusercontent.com"}`
(참고: AWS는 현재 이 프로바이더의 thumbprint를 무시하지만 파라미터 자체는 필수.)

---

### Task 2: IAM 배포 롤 `synapse-gha-deploy-role` 생성

**Files:**
- Create: `C:\workspace\team-project-final\synapse-gateway\.tmp\gha-trust-policy.json` (작업용, 커밋 안 함)
- Create: `C:\workspace\team-project-final\synapse-gateway\.tmp\gha-ecr-policy.json` (작업용, 커밋 안 함)

- [ ] **Step 1: trust policy 파일 작성**

`.tmp\gha-trust-policy.json`:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::963773969059:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:team-project-final/synapse-*:*"
        }
      }
    }
  ]
}
```

- [ ] **Step 2: ECR 최소 권한 policy 파일 작성**

`.tmp\gha-ecr-policy.json`:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "EcrPushPull",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage"
      ],
      "Resource": "arn:aws:ecr:ap-northeast-2:963773969059:repository/synapse/*"
    }
  ]
}
```

- [ ] **Step 3: 롤 생성 + 인라인 폴리시 부착**

Run:
```powershell
aws iam create-role `
  --role-name synapse-gha-deploy-role `
  --assume-role-policy-document file://.tmp/gha-trust-policy.json `
  --description "GitHub Actions OIDC deploy role for team-project-final/synapse-* (ECR push)"
aws iam put-role-policy `
  --role-name synapse-gha-deploy-role `
  --policy-name ecr-push `
  --policy-document file://.tmp/gha-ecr-policy.json
```
Expected: create-role이 `"Arn": "arn:aws:iam::963773969059:role/synapse-gha-deploy-role"` 반환, put-role-policy는 출력 없음(성공).

- [ ] **Step 4: 검증**

Run:
```powershell
aws iam get-role --role-name synapse-gha-deploy-role --query "Role.AssumeRolePolicyDocument" --output json
aws iam get-role-policy --role-name synapse-gha-deploy-role --policy-name ecr-push --query "PolicyDocument.Statement[].Sid" --output json
```
Expected: trust에 `repo:team-project-final/synapse-*:*` 조건 포함, Sid 목록 `["EcrAuth", "EcrPushPull"]`.

- [ ] **Step 5: 작업용 파일 정리**

Run:
```powershell
Remove-Item -Recurse -Force .tmp -Confirm:$false
```

---

### Task 3: `AWS_ROLE_ARN` GitHub 시크릿 등록 (org 우선, repo 폴백)

**Files:** 없음 (GitHub 시크릿만)

- [ ] **Step 1: org 레벨 시도**

Run:
```powershell
gh secret set AWS_ROLE_ARN --org team-project-final --visibility all --body "arn:aws:iam::963773969059:role/synapse-gha-deploy-role"
```
Expected: 성공 시 `✓ Set Actions secret AWS_ROLE_ARN for team-project-final`. **403/권한 오류 시 Step 2로 폴백.**

- [ ] **Step 2 (폴백): repo 레벨 등록**

Run (org 등록 실패 시에만):
```powershell
gh secret set AWS_ROLE_ARN --repo team-project-final/synapse-gateway --body "arn:aws:iam::963773969059:role/synapse-gha-deploy-role"
```
Expected: `✓ Set Actions secret AWS_ROLE_ARN for team-project-final/synapse-gateway`. 이 경우 "다른 서비스 레포에도 동일 등록 필요(org 승격 권장)"를 Task 9의 이슈 코멘트에 포함할 것.

- [ ] **Step 3: 등록 확인**

Run:
```powershell
gh secret list --repo team-project-final/synapse-gateway
```
Expected: `AWS_ROLE_ARN` 행 존재 (org 등록 시 org 시크릿으로 표시될 수 있음 — `gh secret list` 출력에 없으면 org 등록이 성공했는지 `gh api repos/team-project-final/synapse-gateway/actions/secrets`로 확인).

---

### Task 4: ECR 리포 `synapse/gateway` 생성

**Files:** 없음 (AWS 리소스만)

- [ ] **Step 1: 리포 생성 (기존 서비스와 동일한 기본 설정: MUTABLE, scanOnPush=false, AES256)**

Run:
```powershell
aws ecr create-repository --repository-name synapse/gateway --region ap-northeast-2
```
Expected: `"repositoryUri": "963773969059.dkr.ecr.ap-northeast-2.amazonaws.com/synapse/gateway"` 포함 JSON. `RepositoryAlreadyExistsException`이면 이미 존재 — 통과로 간주.

- [ ] **Step 2: 검증**

Run:
```powershell
aws ecr describe-repositories --repository-names synapse/gateway --region ap-northeast-2 --query "repositories[0].{name:repositoryName,mutability:imageTagMutability}" --output json
```
Expected: `{"name": "synapse/gateway", "mutability": "MUTABLE"}`

---

### Task 5: SM 시크릿 `synapse/dev/gateway/redis-password` 시드

**Files:** 없음 (AWS 리소스만)

- [ ] **Step 1: 원본 토큰 형식 확인 (값 비출력)**

Run:
```powershell
$token = aws secretsmanager get-secret-value --secret-id synapse/dev/platform-svc/redis-auth-token --region ap-northeast-2 --query SecretString --output text
"len=$($token.Length) json=$($token.StartsWith('{'))"
```
Expected: `len=<양수> json=False` (plain 문자열). **만약 `json=True`면** ESO가 plain 값을 기대하므로(`gateway-external-secret`은 `property` 지정 없음) JSON에서 토큰 값만 추출해 `$token`에 재할당 후 진행: `$token = ($token | ConvertFrom-Json).<키이름>` — 키 이름은 `($token | ConvertFrom-Json | Get-Member -MemberType NoteProperty).Name`으로 확인.

- [ ] **Step 2: 시크릿 생성**

Run:
```powershell
aws secretsmanager create-secret --name synapse/dev/gateway/redis-password --secret-string $token --region ap-northeast-2 --description "Redis auth token for gateway dev (synapse-dev-redis, platform-svc redis-auth-token과 동일 값)"
```
Expected: `"Name": "synapse/dev/gateway/redis-password"` 포함 JSON. `ResourceExistsException`이면 `aws secretsmanager put-secret-value --secret-id synapse/dev/gateway/redis-password --secret-string $token --region ap-northeast-2`로 대체.

- [ ] **Step 3: 검증 (값 비출력, 존재만 확인)**

Run:
```powershell
aws secretsmanager describe-secret --secret-id synapse/dev/gateway/redis-password --region ap-northeast-2 --query "{Name:Name,Created:CreatedDate}" --output json
```
Expected: Name 일치하는 JSON 반환.

---

### Task 6: synapse-shared PR — `dev-latest` 태그 병행 푸시

**Files:**
- Modify: `synapse-shared:.github/workflows/deploy-service.yml` ("Build and push image" 스텝)

- [ ] **Step 1: synapse-shared 클론 + 브랜치 생성**

Run:
```powershell
git clone https://github.com/team-project-final/synapse-shared.git $env:TEMP\synapse-shared
git -C $env:TEMP\synapse-shared switch -c feat/dev-latest-tag
```
Expected: 클론 성공, 브랜치 `feat/dev-latest-tag` 생성.

- [ ] **Step 2: "Build and push image" 스텝 수정**

`$env:TEMP\synapse-shared\.github\workflows\deploy-service.yml`에서 아래 블록을:
```yaml
      - name: Build and push image
        id: build
        env:
          REGISTRY: ${{ steps.ecr-login.outputs.registry }}
          REPO: ${{ inputs.ecr_repository }}
          TAG: ${{ github.sha }}
        run: |
          IMAGE="${REGISTRY}/${REPO}:${TAG}"
          docker build -f "${{ inputs.build_context }}/${{ inputs.dockerfile }}" -t "$IMAGE" "${{ inputs.build_context }}"
          docker push "$IMAGE"
          echo "tag=${TAG}" >> "$GITHUB_OUTPUT"
```
다음으로 교체:
```yaml
      - name: Build and push image
        id: build
        env:
          REGISTRY: ${{ steps.ecr-login.outputs.registry }}
          REPO: ${{ inputs.ecr_repository }}
          TAG: ${{ github.sha }}
        run: |
          IMAGE="${REGISTRY}/${REPO}:${TAG}"
          DEV_LATEST="${REGISTRY}/${REPO}:dev-latest"
          docker build -f "${{ inputs.build_context }}/${{ inputs.dockerfile }}" -t "$IMAGE" -t "$DEV_LATEST" "${{ inputs.build_context }}"
          docker push "$IMAGE"
          docker push "$DEV_LATEST"
          echo "tag=${TAG}" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 3: 커밋 + 푸시 + PR 생성**

Run:
```powershell
git -C $env:TEMP\synapse-shared add .github/workflows/deploy-service.yml
git -C $env:TEMP\synapse-shared commit -m "ci: dev-latest 태그 병행 푸시 (gitops 계약 — gateway#4)"
git -C $env:TEMP\synapse-shared push -u origin feat/dev-latest-tag
gh pr create --repo team-project-final/synapse-shared --head feat/dev-latest-tag --title "ci: dev-latest 태그 병행 푸시" --body "gitops 계약(dev-latest는 CI 푸시)을 충족하도록 deploy-service.yml이 sha 태그와 함께 dev-latest를 병행 푸시합니다. synapse-gateway#4 참조."
```
Expected: PR URL 출력. **이 PR 머지는 Deploy 검증(Task 8)의 전제가 아님** — sha write-back만으로 App은 Healthy가 됨. 머지 권한이 없으면 PR 링크를 Task 9 이슈 코멘트에 포함하고 진행.

- [ ] **Step 4: 클론 정리**

Run:
```powershell
Remove-Item -Recurse -Force $env:TEMP\synapse-shared -Confirm:$false
```

---

### Task 7: deploy.yml에 `workflow_dispatch` 추가 + 푸시

**Files:**
- Modify: `.github/workflows/deploy.yml:3-8`

- [ ] **Step 1: 트리거 블록 수정**

`.github/workflows/deploy.yml`의 `on:` 블록을:
```yaml
on:
  push:
    branches: [main]
    paths-ignore:
      - 'docs/**'
      - '*.md'
```
다음으로 교체:
```yaml
on:
  push:
    branches: [main]
    paths-ignore:
      - 'docs/**'
      - '*.md'
  workflow_dispatch:
```

- [ ] **Step 2: 커밋**

Run:
```powershell
git add .github/workflows/deploy.yml
git commit -m "ci: deploy 수동 트리거(workflow_dispatch) 추가"
```

- [ ] **Step 3: 푸시 (로컬에 있는 설계 문서 커밋 포함)**

Run:
```powershell
git push origin main
```
Expected: 성공. 이 푸시가 Deploy 워크플로우를 트리거함(workflow 파일 변경은 paths-ignore에 안 걸림) → Task 8의 검증 대상이 됨.
**브랜치 보호로 거부되면**: `git switch -c chore/deploy-dispatch && git push -u origin chore/deploy-dispatch && gh pr create --title "ci: deploy workflow_dispatch 추가 + 설계 문서" --body "이슈 #4 복구 작업. docs + workflow_dispatch." --base main` 후 머지하고, `git switch main && git pull`로 동기화.

---

### Task 8: Deploy 실행 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: Deploy 런 확인 및 감시**

Run:
```powershell
gh run list --workflow=deploy.yml --limit 1
```
Expected: Task 7 푸시로 트리거된 런이 보임. 해당 `<run-id>`로:
```powershell
gh run watch <run-id> --exit-status
```
Expected: `✓ ... Deploy` 성공 종료(빌드 ~2분 소요).
**실패 시**: `gh run view <run-id> --log-failed`로 원인 확인. `Not authorized to perform sts:AssumeRoleWithWebIdentity`면 IAM 전파 지연 가능성 — 2분 대기 후 `gh run rerun <run-id> --failed` 1회 재시도. 권한 오류 지속 시 Task 2의 trust policy `sub` 패턴 재점검.

- [ ] **Step 2: ECR 이미지 확인**

Run:
```powershell
aws ecr describe-images --repository-name synapse/gateway --region ap-northeast-2 --query "imageDetails[].imageTags" --output json
```
Expected: 푸시된 커밋 sha 태그 존재 (synapse-shared PR이 머지된 뒤의 런이면 `dev-latest`도 존재).

- [ ] **Step 3: gitops write-back 확인**

Run:
```powershell
gh api repos/team-project-final/synapse-gitops/commits --jq '.[0].commit.message' 
gh api repos/team-project-final/synapse-gitops/contents/apps/gateway/overlays/dev/kustomization.yaml -H "Accept: application/vnd.github.raw" | Select-String newTag
```
Expected: 최신 커밋 메시지 `deploy: bump gateway to <sha>`, kustomization의 `newTag`가 해당 sha.

- [ ] **Step 4: 클러스터 측 확인 (가능한 경우만)**

Run:
```powershell
kubectl get pods -n synapse-dev -l app.kubernetes.io/name=gateway 2>&1
```
kubeconfig가 없으면 스킵하고 Task 9 코멘트에 "클러스터 측 acceptance(App Healthy/SecretSynced)는 라이브 검증 담당 확인 필요(ESO refresh 5m)"를 명시. 가능하면 Expected: pod `gateway-*` Running, ESO refresh(≤5m) 후 `kubectl get externalsecret -n synapse-dev gateway-external-secret` → `SecretSynced=True`.

---

### Task 9: 이슈 #4 코멘트 + 마무리

**Files:** 없음

- [ ] **Step 1: 수행 내역 코멘트**

Run (실제 결과에 맞춰 본문 수정 — 특히 org/repo 시크릿 여부, shared PR 링크, 클러스터 확인 여부):
```powershell
gh issue comment 4 --repo team-project-final/synapse-gateway --body @'
## 복구 작업 완료 (CI/AWS 측)

근본 원인은 이슈의 ①②에 더해 **GitHub OIDC 인프라 자체 부재**였습니다 — `AWS_ROLE_ARN` 시크릿이 없어 reusable deploy 워크플로우가 전 서비스에서 즉시 실패하고 있었습니다.

### 수행 내역
- [x] IAM: GitHub OIDC 프로바이더 + `synapse-gha-deploy-role` 생성 (trust: `repo:team-project-final/synapse-*:*`, 권한: `repository/synapse/*` ECR push 한정)
- [x] GitHub 시크릿 `AWS_ROLE_ARN` 등록 (<org/repo 레벨 — 실제 결과 기입>)
- [x] ECR `synapse/gateway` 생성
- [x] SM `synapse/dev/gateway/redis-password` 시드 (synapse-dev-redis auth token, platform-svc와 동일 값)
- [x] Deploy 워크플로우 성공 — 이미지 푸시 + gitops newTag write-back 확인 (run: <run-url>)
- [x] dev-latest 병행 푸시: synapse-shared PR <PR-url>

### 남은 확인 (클러스터 측)
- [ ] `synapse-gateway-dev` App Synced/Healthy, pod Running
- [ ] `gateway-external-secret` SecretSynced=True (ESO refresh ≤5m)

코드/매니페스트 배선(8080, readiness, rate-limit redis-password)은 점검 결과 정상 — 변경 없음.
'@
```
Expected: 코멘트 URL 출력.

- [ ] **Step 2 (선택, 사용자 확인 게이트): 구형 ECR `synapse-gateway` 삭제**

**실행 전 반드시 사용자에게 확인.** 잘못된 네이밍의 구형 리포(구 이미지 `1.0.0`/`latest` 포함)로, 현행 어디서도 참조하지 않음을 확인한 경우에만:
```powershell
aws ecr delete-repository --repository-name synapse-gateway --region ap-northeast-2 --force
```
보류 시 이슈 코멘트에 "구형 `synapse-gateway` ECR 리포는 혼동 방지 위해 정리 권장"으로 기록.

---

## Self-Review 결과

- **Spec 커버리지**: 설계 섹션 1→Task 1·2, 섹션 2→Task 3, 섹션 3→Task 4·5(+Task 9 Step 2), 섹션 4→Task 6, 섹션 5→Task 7, 섹션 6→Task 8·9. 누락 없음.
- **Placeholder**: Task 9 코멘트 본문의 `<...>` 자리는 실행 시 확정되는 값(런 URL 등)으로, 실행 단계에서 치환 — 명시적 지시 포함.
- **일관성**: 롤 이름 `synapse-gha-deploy-role`, ECR `synapse/gateway`, SM `synapse/dev/gateway/redis-password` 전 태스크 일치 확인.
