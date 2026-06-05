# gateway dev 배포 파이프라인 복구 설계 (이슈 #4)

- 날짜: 2026-06-05
- 대상 이슈: [synapse-gateway#4](https://github.com/team-project-final/synapse-gateway/issues/4) — gateway dev 미배포 (gitops #91/#121 차단)
- 접근: OIDC 정석 구축 (승인됨)

## 배경 / 근본 원인

이슈 #4가 지목한 ECR 리포·SM 시크릿 부재에 더해, 상류 원인을 조사로 확인했다.

| # | 원인 | 증거 |
|---|------|------|
| 1 | **GitHub Actions OIDC 인프라가 AWS에 없음** — `token.actions.githubusercontent.com` 프로바이더·배포 롤·`AWS_ROLE_ARN` 시크릿 전부 부재 | `aws iam list-open-id-connect-providers`에 EKS 프로바이더만 존재. 레포 시크릿은 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`GITOPS_TOKEN`뿐. reusable 워크플로우(`synapse-shared/deploy-service.yml`)는 `AWS_ROLE_ARN`을 필수 시크릿으로 요구 → 잡이 스텝 0개로 3~4초 만에 실패. **engagement-svc 등 전 서비스 Deploy가 5/27 이후 동일하게 실패 중** |
| 2 | **ECR `synapse/gateway` 부재** — 잘못된 이름의 구형 리포 `synapse-gateway`만 존재 (`1.0.0`, `latest` 등 구 CI 산출물) | `aws ecr describe-repositories`. 다른 서비스는 `synapse/<name>` 컨벤션 |
| 3 | **SM `synapse/dev/gateway/redis-password` 부재** | ElastiCache `synapse-dev-redis`(AuthToken enabled)는 존재. platform-svc가 동일 클러스터 토큰을 `synapse/dev/platform-svc/redis-auth-token`에 보유 → 값 복사로 시드 가능 |
| 4 | 앱 코드·gitops 매니페스트 배선은 **정상** — 변경 불필요 | 8080 listen(`application.yml`, `Dockerfile`), `RedisRateLimiter` → `SPRING_DATA_REDIS_PASSWORD` env 배선, dev overlay의 SSL/host는 Spring relaxed binding으로 매핑. readiness probe는 Spring Boot가 K8s에서 자동 활성화 |

로컬 AWS 자격: `synapse-admin` (계정 963773969059, 이슈 ECR 계정과 일치) → 전 항목 직접 해소 가능.

## 설계

### 1. AWS IAM — GitHub OIDC 신뢰 구축 (신규 생성만, 기존 리소스 변경 없음)

- OIDC 프로바이더 `token.actions.githubusercontent.com` 생성, audience `sts.amazonaws.com`
- 롤 `synapse-gha-deploy-role` 생성
  - Trust: Federated = 위 프로바이더, 조건 `aud = sts.amazonaws.com`, `sub` StringLike `repo:team-project-final/synapse-*:*`
  - 인라인 폴리시(최소 권한): `ecr:GetAuthorizationToken`(리소스 `*`) + ECR push/pull 액션을 `arn:aws:ecr:ap-northeast-2:963773969059:repository/synapse/*`로 한정

### 2. GitHub 시크릿

- `AWS_ROLE_ARN` = 생성된 롤 ARN
- **org 레벨 우선 시도** (`gh secret set --org team-project-final`) → 전 서비스 동시 해소
- gh 토큰 권한 부족(403) 시: 이 레포에 repo 레벨로 등록하고, org 승격 필요성을 이슈 #4 코멘트에 기록

### 3. AWS 리소스 시드

- ECR `synapse/gateway` 생성 — 기존 `synapse/engagement-svc` 리포 설정(스캔, 태그 변경 가능성 등)과 동일하게
- SM `synapse/dev/gateway/redis-password` 생성 — 값은 `synapse/dev/platform-svc/redis-auth-token`에서 복사 (동일 ElastiCache 클러스터 auth token)
- (선택) 구형 ECR 리포 `synapse-gateway` 삭제 — 파괴적 작업이므로 **실행 직전 사용자 확인 필수**, 보류 가능

### 4. synapse-shared PR — dev-latest 태그 병행 푸시

- 현재 reusable 워크플로우는 `github.sha` 태그만 푸시 + gitops kustomization write-back
- gitops 계약("`dev-latest`는 CI 푸시")을 맞추기 위해 `deploy-service.yml`의 build/push 스텝에 `dev-latest` 태그 병행 푸시 추가 (가산적 변경, 전 서비스 일관 적용)
- 별도 브랜치 → PR로 제출 (synapse-shared는 main 직푸시 금지 가정)

### 5. 이 레포 변경 (최소)

- 코드·`Dockerfile`·`application.yml` 변경 없음
- `.github/workflows/deploy.yml`에 `workflow_dispatch` 트리거 추가 — 코드 변경 없이 수동 재배포 가능

### 6. 검증 (이슈 acceptance 대응)

1. Deploy 워크플로우 재실행(`gh run rerun` 또는 `workflow_dispatch`) → 성공 확인
2. `aws ecr describe-images --repository-name synapse/gateway`로 이미지 태그 존재 확인
3. gitops `apps/gateway/overlays/dev/kustomization.yaml` newTag bump 커밋 확인
4. SM 시크릿 존재 확인. SecretSynced/App Healthy 등 클러스터 측 acceptance는 kubectl 접근 가능 시 직접 확인, 불가 시 이슈 코멘트로 라이브 검증 담당에게 인계 (ESO refreshInterval 5m 감안)
5. 이슈 #4에 수행 내역·검증 결과 코멘트

### 에러 처리

- IAM 롤 전파 지연으로 첫 OIDC AssumeRole이 실패할 수 있음 → 1~2분 후 1회 재시도
- ECR push 권한 오류 시 인라인 폴리시 리소스 경로 재점검
- org 시크릿 403 시 repo 시크릿 폴백 (섹션 2)

## 범위 외 (YAGNI)

- prod 배포 경로 (이슈는 dev 한정)
- Image Updater semver 태그 푸시 (sha write-back으로 충분, 필요 시 후속)
- application.yml probes 명시 설정 (K8s 자동 활성화로 충분)
