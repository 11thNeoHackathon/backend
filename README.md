# 청춘교환 백엔드

가족 연결, 관계별 책, 챕터, 미션, 감상을 제공하는 Spring 백엔드입니다.

## 개발 기준

공통 작업 절차는 [AGENTS.md](AGENTS.md)를 따릅니다. 경로·메서드·요청·정상 응답·기본 오류를 Swagger에 먼저 명세하고, API 변경과 함께 갱신합니다. 로그인 도입은 별도로 결정하며, 책 접근과 감상 작성 권한은 서버에서 확인합니다.

## 요청 예시

> 감상 제출 후 보낸 사람의 To-do 조회에도 완료 상태가 반영되는지 검증해줘.

빈 감상이나 ‘나중에 하기’는 대기 상태를 유지합니다. 감상을 저장하면 완료되고, 재요청해도 감상이 중복되지 않아야 합니다. 보낸 사람은 해당 감상을 읽을 수 있고, 다른 책의 참여자는 접근할 수 없습니다. 연동과 발표 전에는 [공통 시연 시나리오](docs/시연%20시나리오.md)를 사용합니다.

## 서버 1 실행

Java 21과 MySQL 8.0.16 이상이 필요합니다. 비어 있는 `hackathon` 데이터베이스에서 앱이 시작되면 Flyway가 확정 ERD의 서버 1 테이블 다섯 개를 생성합니다. 적용한 테이블은 `participant`, `participant_session`, `book`, `book_participant`, `book_invitation`입니다. `book`의 상태는 책의 두 자리 연결 여부로 계산합니다. 생성·만료 시각은 UTC로 저장합니다.

| 환경 변수 | 설정 |
| --- | --- |
| `DB_URL` | 필수. RDS 예시 `jdbc:mysql://<rds-endpoint>:3306/hackathon?sslMode=VERIFY_IDENTITY`. 로컬 예시 `jdbc:mysql://localhost:3306/hackathon`. |
| `DB_USERNAME`, `DB_PASSWORD` | 필수. 연결 계정과 비밀번호. |
| `INVITE_BASE_URL` | `/invite/{code}` 화면을 서비스하는 웹 주소. 기본 `http://localhost:8080`. |
| `FRONTEND_ORIGIN` | 별도 프론트 오리진이 필요할 때만 허용할 정확한 오리진을 지정합니다. 프론트 요청에서 쿠키를 포함합니다. |
| `COOKIE_SECURE` | 기본값 `true`. 로컬 HTTP 테스트에서만 `false`로 지정합니다. |

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/hackathon'
$env:DB_USERNAME = '로컬 DB 사용자'
$env:DB_PASSWORD = '로컬 DB 비밀번호'
$env:COOKIE_SECURE = 'false'
.\gradlew.bat bootRun
```

RDS에서 `VERIFY_IDENTITY`를 사용할 때는 [RDS 루트 CA](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)를 애플리케이션의 신뢰 저장소에 등록합니다. Flyway 연결 계정에는 `hackathon`에서 최초 마이그레이션을 실행할 권한이 필요합니다. macOS/Linux는 `COOKIE_SECURE=false ./gradlew bootRun`을 사용합니다. 시작 후 `http://localhost:8080/swagger-ui/index.html`에서 [API 계약](src/main/resources/static/openapi/server1.yaml)을 호출할 수 있습니다. Swagger 시연은 일반 창에서 `POST /api/v1/participants` → `POST /api/v1/books` → 링크 코드 복사, 다른 브라우저 프로필에서 초대 미리보기 → 닉네임으로 수락 순으로 진행합니다. 두 브라우저 모두 `GET /api/v1/books`에서 같은 책이 `ACTIVE`인지 확인합니다.

초대 링크 유효 기간은 24시간, 기기 쿠키 유효 기간은 30일입니다. 초대 링크의 UI는 프론트에서 처리하며, API에서 받은 코드로 미리보기와 수락 요청을 보냅니다. 프론트를 별도 오리진에 올릴 때도 같은 사이트의 HTTPS 배치를 권장합니다. 쿠키를 삭제하거나 쿠키가 만료된 기기의 기존 책을 복구하는 기능은 제공하지 않습니다.

통합 테스트는 실제 MySQL 8의 별도 테스트 데이터베이스를 사용합니다. 운영용 `DB_*`와 별개로 `TEST_DB_URL`(예: `jdbc:mysql://localhost:3306/hackathon_test`), `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`를 설정한 후 `./gradlew test` 또는 `.\gradlew.bat test`를 실행합니다. GitHub Actions는 PR과 `main` 푸시에서 테스트용 MySQL을 띄워 Java 21로 `test bootJar`를 실행합니다.

같은 MySQL 데이터베이스에 V1 가족 연결, V2 공유 챕터·이야기, V3 미션 감상 마이그레이션이 순서대로 적용됩니다. Swagger UI에서 `가족 연결`과 `챕터와 이야기` 문서를 선택할 수 있습니다. 서버 2의 요청 순서와 공개 규칙은 [서버 2 구현](docs/서버2%20구현.md)을 참고하세요. 두 사람의 동시 감상 제출과 공개된 미션 문구 변경도 통합 테스트에서 확인합니다.

## EC2 자동 배포

PR에서는 테스트용 MySQL 8과 Java 21로 테스트·빌드를 실행합니다. `main`에 푸시하거나 PR을 병합하면 검증한 JAR를 AWS Systems Manager Session Manager의 SSH 터널로 EC2에 전송합니다. EC2의 보안 그룹에 GitHub 러너용 SSH 인바운드 규칙을 추가할 필요가 없습니다. GitHub는 OIDC에서 발급받은 임시 AWS 자격 증명을 사용합니다.

1. AWS에서 배포 대상 EC2가 Systems Manager 관리형 노드에서 `Online`인지 확인합니다. EC2용 인스턴스 역할에 `AmazonSSMManagedInstanceCore`를 추가하고, SSM Agent가 AWS 엔드포인트로 HTTPS 통신할 수 있어야 합니다. Session Manager 터널에서 EC2의 `sshd`가 22번 포트로 요청을 받아야 합니다. [EC2 권한 설정](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html)
2. GitHub Actions용 AWS IAM 역할을 준비합니다. OIDC 제공자는 `token.actions.githubusercontent.com`, 신뢰 조건 `aud`는 `sts.amazonaws.com`, `sub`는 `repo:11thNeoHackathon/backend:environment:production`으로 설정합니다. 이 역할에 대상 EC2 인스턴스 ARN과 `arn:aws:ssm:<리전>::document/AWS-StartSSHSession`을 대상으로 한 `ssm:StartSession`만 허용합니다. `ssm:TerminateSession` 및 `ssmmessages:OpenDataChannel`이 필요한 경우 AWS 예제의 세션 소유자 조건을 적용합니다. [OIDC 신뢰 조건](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create_for-idp_oidc.html) · [SSH 세션 IAM 정책](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-getting-started-enable-ssh-connections.html)
3. 저장소의 **Settings → Environments → production**에서 배포 브랜치를 `main`으로 제한합니다. 아래 값을 **Environment secrets**와 **Environment variables**에 등록합니다.

   | 종류 | 이름 | 등록할 값 |
   | --- | --- | --- |
   | 시크릿 | `EC2_HOST` | 기존 `known_hosts`에서 조회할 때 사용한 서버 주소. SSH 호스트 키 별칭으로 사용합니다. |
   | 시크릿 | `EC2_USER` | EC2의 SSH 사용자 이름. |
   | 시크릿 | `EC2_SSH_PRIVATE_KEY` | EC2 접속용 `.pem` 개인키 전체. GitHub 입력창에 직접 등록합니다. |
   | 시크릿 | `EC2_KNOWN_HOSTS` | 검증된 EC2 SSH 호스트 공개키의 `known_hosts` 줄. |
   | 변수 | `EC2_AWS_REGION` | 배포 대상 EC2 리전 코드. |
   | 변수 | `EC2_AWS_ROLE_ARN` | GitHub Actions용 OIDC 역할의 ARN. |
   | 변수 | `EC2_INSTANCE_ID` | 배포 대상 EC2의 `i-` 인스턴스 ID. |
   | 변수 | `EC2_HEALTH_BASE_URL` | 선택 항목. 기본값은 `http://127.0.0.1`입니다. HTTPS에서는 Nginx 인증서의 실제 도메인을 포함한 `https://api.example.com` 형태를 사용합니다. |

   ```powershell
   ssh-keygen -F '<서버 주소>' -f "$env:USERPROFILE\.ssh\known_hosts" | Where-Object { $_ -notmatch '^#' }
   ```

4. **Settings → Secrets and variables → Actions → Variables**에서 저장소 변수 `EC2_DEPLOY_ENABLED=true`로 배포를 활성화합니다. 병합 뒤에는 **Actions → Backend CI/CD → Run workflow**에서 `main`을 선택해 다시 실행할 수 있습니다. `TargetNotConnected`가 발생하면 EC2의 SSM 등록 상태와 인스턴스 역할을 확인합니다.

배포 계정은 `/home/ubuntu`에 쓰기 권한과 `sudo -n /usr/bin/systemctl restart hackathon.service` 실행 권한이 필요합니다. `app.jar` 소유자가 배포 계정과 다르면 `sudo -n /usr/bin/chown --reference=...`으로 소유권과 그룹을 유지할 수 있어야 합니다.

배포는 Spring Boot와 Nginx의 `/openapi/server1.yaml`이 각각 `200`인지, Nginx를 통해 세션 쿠키 없이 호출한 `/api/v1/participants/me`가 `401`과 `SESSION_REQUIRED`를 반환하는지 확인합니다. HTTPS 확인 요청은 Nginx에 로컬로 연결하며 실제 도메인의 인증서를 검증합니다. 확인에 실패하면 직전 JAR를 복원합니다. 운영 DB 연결은 기존 `/etc/hackathon.env`를 사용합니다. 이미 적용된 Flyway 마이그레이션은 JAR 복원 시 유지되므로, 마이그레이션은 직전 애플리케이션과 호환되게 작성합니다.

## 배포 프론트 연동 확인

- 프론트와 API가 다른 오리진이면 `FRONTEND_ORIGIN`에 경로 없이 프론트의 정확한 오리진을 설정합니다. 예를 들어 `https://app.example.com`입니다. 프론트 요청은 `credentials: 'include'`를 사용하고, API는 설정한 오리진의 `GET`, `POST`, `PUT`, `DELETE`를 허용합니다.
- 브라우저에서 초대 수락 응답으로 `participant_session` 쿠키가 저장되는지, 이어서 `GET /api/v1/participants/me`에 쿠키가 실려 `200`이 되는지 확인합니다. 현재 `SameSite=Lax`는 `https://app.example.com`과 `https://api.example.com`처럼 같은 사이트인 배치에 사용할 수 있습니다. 서로 다른 사이트라면 `credentials: 'include'`로도 `Lax` 쿠키가 전송되지 않으므로, 배포 도메인과 쿠키 방식을 확정한 뒤 실제 브라우저에서 확인합니다.
- 서버 2 연동 후 배포된 프론트에서 이야기·감상 저장(`PUT`)과 빈 챕터 삭제(`DELETE`)를 실행합니다. 브라우저에서 `OPTIONS` 사전 요청의 `Access-Control-Allow-Origin`, 허용 메서드, 쿠키 허용 여부와 실제 요청 결과를 확인합니다. 로컬 테스트의 사전 요청 검증과 배포 프론트 연동 검증은 별개입니다.

## 다른 API에서 공통 컴포넌트 사용

`com.cheongchun.common.auth`의 `ParticipantContext`와 `BookAccessService`를 생성자로 주입하고, 요청 안에서 아래 순서로 호출합니다. 쿠키가 없거나 만료·폐기됐으면 `401`, 책이 없거나 참여하지 않은 책이면 같은 `404`를 반환합니다. 요청 본문의 ID 대신 여기서 얻은 `participantId`로 작성자나 감상 제출자를 확인합니다.

```java
long participantId = participantContext.currentParticipantId();
BookParticipant membership = bookAccessService.requireParticipant(bookId, participantId);
```

이후 작성자·미션 수행자 등 기능별 권한이 부족하면 `ApiException.forbidden(message)`를 던집니다. `com.cheongchun.common.api`의 `ApiResponse.success(data)`를 성공 응답에 쓰고, 생성은 HTTP `201`, 조회 및 변경은 `200`을 사용합니다. 잘못된 입력은 `ApiException.invalidRequest(message)`로 처리합니다. 전역 예외 처리기가 실패 응답을 만들어주며, 프론트는 실패 코드를 `error.code`에서 읽습니다. 사용되거나 만료된 초대 등 상태 충돌은 기존처럼 `409`입니다.
