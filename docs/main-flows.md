# Các luồng chính — Feature Flag Platform

Tài liệu này mô tả **7 luồng nghiệp vụ chính** của hệ thống. Mỗi luồng gồm:

1. **Vấn đề API giải quyết** — tại sao endpoint đó tồn tại
2. **Cơ chế** — code thật chạy như thế nào (kèm đường dẫn file)
3. **Request mẫu có sẵn data** — copy chạy được ngay
4. **Case lỗi nên test** — để chứng minh cơ chế hoạt động

> Muốn chạy một mạch từ đầu đến cuối: xem [`demo/api-demo-flow.http`](./demo/api-demo-flow.http) (IntelliJ HTTP Client, tự lưu biến giữa các request).

---

## Chuẩn bị

```bash
docker compose up -d          # PostgreSQL
./mvnw spring-boot:run        # app chạy ở cổng 8081
```

| | |
|---|---|
| Base URL | `http://localhost:8081` |
| Swagger UI | http://localhost:8081/swagger-ui.html |
| OpenAPI JSON | http://localhost:8081/api-docs |

**Trên Windows PowerShell dùng `curl.exe`** (không phải `curl`, vì đó là alias của `Invoke-WebRequest`), hoặc chạy trong Git Bash. Các ví dụ dưới đây viết theo cú pháp Bash.

Biến dùng chung — set dần trong lúc chạy:

```bash
BASE=http://localhost:8081
ACCESS=          # điền sau bước login
REFRESH=
ORG_ID=
PROJECT_ID=
ENV_ID=
FLAG_ID=
API_KEY=
```

### Ba loại xác thực, không dùng lẫn nhau

| Nhóm endpoint | Header xác thực |
|---|---|
| `/api/v1/auth/**` | không cần |
| Admin API (`/organisations`, `/projects`, `/environments`, `/flags`) | `Authorization: Bearer <accessToken>` |
| SDK API (`/api/v1/sdk/**`) | `X-Environment-Key: <apiKey>` |
| `/actuator/**` (trừ health) | HTTP Basic, user `metrics` |

### Bảng mã lỗi chung (`exception/GlobalExceptionHandler.java`)

| Tình huống | HTTP | Nghĩa |
|---|---|---|
| Thiếu / sai / hết hạn JWT | **401** | "anh là ai" |
| Refresh token sai / revoked / reuse | **401** | |
| Có đăng nhập nhưng không đủ role | **403** | "anh không được phép" |
| Không tìm thấy tài nguyên | 404 | |
| Trùng key / slug / tên | 409 | |
| Body không hợp lệ (Bean Validation) | 400 | |
| Vượt rate limit | 429 | kèm `Retry-After` |

Toàn bộ body lỗi theo chuẩn **RFC 7807 `application/problem+json`**.

### Thứ tự bắt buộc

```
register → login → org → project → environment → flag → bật flag → SDK đọc
```

**Tạo environment TRƯỚC, tạo flag SAU.** Khi tạo flag hệ thống tự sinh state row cho mọi environment đang tồn tại (`FeatureFlagServiceImpl.java:74`). Chiều ngược lại **không** có backfill: environment tạo sau sẽ không có state cho các flag đã tồn tại.

---

# Luồng 1 — Auth: JWT ngắn hạn + refresh token xoay vòng

### Vấn đề giải quyết

Access token sống lâu thì bị lộ là mất trắng; sống ngắn thì user phải đăng nhập lại liên tục. Luồng này tách đôi: **access token 15 phút** (stateless, không revoke được nên phải ngắn) + **refresh token 14 ngày** (lưu hash trong DB nên revoke được).

Vấn đề khó hơn: refresh token bị đánh cắp thì sao? Kẻ trộm và người dùng thật cùng cầm một token. Hệ thống giải quyết bằng **rotation + reuse detection**: mỗi lần refresh sinh token mới và đánh dấu token cũ đã dùng. Nếu một token đã dùng bị trình lại lần nữa ⇒ chắc chắn có 2 bên đang giữ token ⇒ **thu hồi toàn bộ family**, cả hai bên phải đăng nhập lại.

### Cơ chế

- `AuthServiceImpl.java` — register / login / refresh / logout
- `RefreshTokenServiceImpl.java:48` — `rotate()`, nơi chứa toàn bộ logic phát hiện reuse
- `RefreshTokenFamilyRevoker` — revoke chạy trong transaction `REQUIRES_NEW` để không bị rollback cuốn theo khi `rotate()` throw
- Token là 32 byte `SecureRandom` → hex 64 ký tự; DB chỉ lưu **SHA-256 hash**
- `security/JwtAuthenticationFilter.java` — validate Bearer token cho mọi request admin
- `security/ProblemDetailAuthenticationEntryPoint.java` — không có token ⇒ **401** (không phải 403)

### Request

**1.1 Đăng ký (→ 201, không có body)**

```bash
curl -i -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "owner@aibles.com",
    "password": "Password123!",
    "firstName": "Trang",
    "lastName": "Bui"
  }'
```

Đăng ký thêm 1 user thứ hai để lát nữa test invite member:

```bash
curl -i -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "dev@aibles.com",
    "password": "Password123!",
    "firstName": "Dev",
    "lastName": "Two"
  }'
```

**1.2 Đăng nhập (→ 200)**

```bash
curl -s -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "owner@aibles.com", "password": "Password123!"}'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
  "refreshToken": "9f2c1a...64 ký tự hex",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "userId": "3f1b...",
  "email": "owner@aibles.com"
}
```

Lưu lại: `ACCESS=<accessToken>`, `REFRESH=<refreshToken>`.

> Không có API tra userId theo email. Muốn có `userId` của `dev@aibles.com` để invite, hãy login bằng tài khoản đó và lấy `userId` trong response.

**1.3 Refresh (→ 200, trả về cặp token MỚI)**

```bash
curl -s -X POST $BASE/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\": \"$REFRESH\"}"
```

Sau bước này **phải cập nhật lại `REFRESH`** bằng giá trị mới.

**1.4 Đăng xuất (→ 204)**

```bash
curl -i -X POST $BASE/api/v1/auth/logout \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\": \"$REFRESH\"}"
```

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| **Reuse detection** | Gọi `/refresh` với `REFRESH` cũ **hai lần** | Lần 1: 200. Lần 2: **401** `"Refresh token reuse detected"`. Sau đó token mới sinh ra ở lần 1 **cũng chết** — cả family bị revoke |
| Sau logout | Dùng lại refresh token đã logout | 401 `"Refresh token has been revoked"` |
| Không token | `GET /api/v1/organisations` không có header | **401** + header `WWW-Authenticate: Bearer` |
| Token rác | `Authorization: Bearer abc.def.ghi` | 401 |
| Password ngắn | register với `"password": "123"` | 400 (`@Size(min = 8)`) |
| Email trùng | register lại `owner@aibles.com` | 409 |
| Brute force | Gọi `/api/v1/auth/login` 11 lần trong 1 phút | request thứ 11 → **429** + `Retry-After` |

---

# Luồng 2 — Tổ chức & phân quyền: Org → Project → Environment

### Vấn đề giải quyết

Một hệ thống feature flag dùng chung cho nhiều team phải trả lời được: *ai được đọc, ai được sửa, ai được xoá cái gì*. Hệ thống dựng cây 3 tầng **Organization → Project → Environment**, và gắn role ở **tầng Organization** (`OWNER` / `ADMIN` / `VIEWER`).

Người tạo org tự động thành `OWNER` — giải quyết bài toán "quả trứng và con gà" (không ai cấp quyền cho người đầu tiên được).

Nhưng chỉ 3 role ở tầng org thì quá thô cho hai tình huống thực tế:

1. **Một người cần quyền cao ở đúng một project.** Cho họ ADMIN toàn org là cấp thừa quyền lên mọi project khác.
2. **Bật/tắt flag ở production khác hẳn ở dev**, dù cùng là một hành động. Nếu chỉ xét role, ADMIN sửa được dev thì cũng sửa được prod.

Vì vậy hệ thống dùng **ABAC** (attribute-based): quyền không quyết định bằng role, mà bằng **`Action`** — và thuộc tính của tài nguyên (môi trường có phải PRODUCTION không, có đang trong giờ được phép sửa không) tham gia vào quyết định.

### Cơ chế

`service/impl/PermissionService.java` là **Policy Decision Point** duy nhất. Controller không chứa logic phân quyền — chỉ định tuyến; mọi kiểm tra nằm trong service impl.

**Tập quyền hiệu dụng** — hợp của role org và grant theo project:

```
effectiveActions(user, project) = actionsForRole(org role) ∪ grantActions(PROJECT grant)
effectiveActions(user, org)     = actionsForRole(org role)     // grant không áp dụng ở tầng org
```

- **`Action`** (`domain/enums/Action.java`) — 27 hành động, là *từ vựng duy nhất* mà PDP kiểm tra. Role (dựng sẵn lẫn tự định nghĩa) chỉ là một tập `Action`.
- **`PermissionGrant`** — nâng quyền một người trên **một project**, mang theo hoặc một built-in role hoặc một `CustomRole`. Grant chỉ **cộng thêm** quyền: OWNER/ADMIN của org không bao giờ bị grant hẹp kéo xuống.
- **`CustomRole`** — tập `Action` có tên, phạm vi org (`POST /organisations/{orgId}/roles`).

**Hai luật thuộc tính chồng lên trên phép kiểm tra `Action`:**

- **Luật production** — mọi action chạm vào environment `PRODUCTION` bị viết lại thành biến thể `*_PRODUCTION` **chỉ OWNER mới có**. Bảng `PRODUCTION_ELEVATED` gồm 4 cặp:

  | Action gốc | Khi chạm PRODUCTION |
  |---|---|
  | `FLAG_STATE_UPDATE` | `FLAG_STATE_UPDATE_PRODUCTION` |
  | `FLAG_ARCHIVE` | `FLAG_ARCHIVE_PRODUCTION` |
  | `ENV_ROTATE_KEY` | `ENV_ROTATE_KEY_PRODUCTION` |
  | `ENV_DELETE` | `ENV_DELETE_PRODUCTION` |

  **Archive nằm trong bảng này là cố ý**: flag archived bị lọc khỏi mọi response SDK, nên archive chính là một công tắc tắt trá hình. Rotate key và xoá env thì cắt đứt hẳn SDK. Nếu chỉ canh `FLAG_STATE_UPDATE` thì luật vòng qua được dễ dàng. Bất kỳ action mới nào làm đổi hành vi production đều phải thêm vào bảng.

- **Luật change window** — environment có thể khai báo `changeWindowStartHour`/`changeWindowEndHour`; action đã bị nâng cấp ở luật trên còn phải rơi vào khung giờ đó. Cửa sổ được phép vắt qua nửa đêm (`22 → 6`); để trống hoặc start == end nghĩa là không giới hạn. Sửa chính khung giờ này cần `ENV_MANAGE_PROTECTION` (OWNER).

**Action được đo trên môi trường nào** (`productionEnvironments()`):
- Call site có nêu đích danh environment ⇒ chỉ xét môi trường đó.
- Call site chỉ có project (archive/unarchive flag) ⇒ xét **mọi** production env dưới project, **cửa sổ nghiêm nhất thắng** — một cửa sổ đóng là chặn.
- Action ngoài `PRODUCTION_ELEVATED` ⇒ bỏ qua hoàn toàn, không tốn thêm query.

**Các mốc code khác:**

- `OrganizationServiceImpl.java:58` — tự tạo `OrganizationMember` role `OWNER` cho người tạo org
- `OrganizationServiceImpl.java:136` — chặn mời member có role **cao hơn role của chính mình** (ADMIN không thể tự nhân bản ra OWNER)
- `OrganizationServiceImpl.java:165` — chặn xoá `OWNER` cuối cùng (nếu không org sẽ thành mồ côi, không ai sửa được nữa)

> **Nợ kỹ thuật cần biết:** các method `requireRole` / `requireRoleForProject` / `requireRoleForEnvironment` là API **trước ABAC**, còn giữ lại làm adapter cho call site chưa migrate. Adapter **không thấy** luật production lẫn change window (không có `Action`, không đọc `EnvType`), và grant mang custom role cũng không thoả được adapter. Hiện còn 10 chỗ dùng: `WebhookSubscriptionServiceImpl` (7), `EnvironmentTransferServiceImpl` (2), `FlagHygieneServiceImpl` (1). **Code mới phải dùng `check(Action, ResourceRef)`.**

### Ma trận quyền

Role dựng sẵn = tập `Action` cố định (`PermissionService.buildRoleActions()`). Mỗi cấp bao trùm cấp dưới:

| Role | Có thêm gì |
|---|---|
| `VIEWER` | `FLAG_READ`, `ENV_READ`, `PROJECT_READ`, `AUDIT_READ` |
| `ADMIN` | + tạo/sửa flag, archive flag, bật/tắt flag, tạo/sửa env, rotate key, tạo/sửa project, sửa org, mời & quản lý member, quản lý grant & custom role |
| `OWNER` | + xoá flag/env/project/org, `ENV_MANAGE_PROTECTION`, và **toàn bộ 4 action `*_PRODUCTION`** |

Đọc theo hành động:

| Hành động | Cần |
|---|---|
| Xem org / project / environment / flag / audit log | VIEWER |
| Tạo & sửa project, environment, flag | ADMIN |
| Mời / gỡ member, cấp grant, quản lý custom role | ADMIN |
| Bật/tắt flag, archive flag, rotate API key — ở env **non-production** | ADMIN |
| Bật/tắt flag, archive flag, rotate API key, xoá env — ở env **PRODUCTION** | **OWNER** + trong change window |
| Xoá org, xoá project, xoá environment | OWNER |
| Sửa `type` / change window của environment | OWNER (`ENV_MANAGE_PROTECTION`) |

Hoặc bỏ hẳn bảng trên: cấp một `CustomRole` chứa đúng những `Action` cần, rồi grant nó cho người đó trên đúng một project.

### Request

**2.1 Tạo organisation (→ 201)**

```bash
curl -s -X POST $BASE/api/v1/organisations \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"name": "Aibles Java", "slug": "aibles-java"}'
```

`slug` phải khớp `^[a-z0-9-]+$`. Lưu `ORG_ID` từ response.

**2.2 Danh sách org của tôi (có phân trang)**

```bash
curl -s "$BASE/api/v1/organisations?page=0&size=20" -H "Authorization: Bearer $ACCESS"
```

Mọi endpoint list đều trả `PageResponse`:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```

Mặc định `size=20`, tối đa `size=100` — xin lớn hơn sẽ bị kẹp xuống 100 (`config/PaginationConfig.java`).

**2.3 Mời member (→ 201)**

```bash
curl -s -X POST $BASE/api/v1/organisations/$ORG_ID/members \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"userId": "<userId của dev@aibles.com>", "role": "VIEWER"}'
```

`role` ∈ `OWNER` | `ADMIN` | `VIEWER`.

**2.4 Tạo project (→ 201)**

```bash
curl -s -X POST $BASE/api/v1/projects \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{
    \"organisationId\": \"$ORG_ID\",
    \"name\": \"Mobile Banking\",
    \"description\": \"App khach hang ca nhan\"
  }"
```

Lưu `PROJECT_ID`. Lưu ý tên field là `organisationId` (chữ **s**, kiểu Anh).

**2.5 Các endpoint còn lại**

```bash
curl -s "$BASE/api/v1/projects?organisationId=$ORG_ID"     -H "Authorization: Bearer $ACCESS"
curl -s "$BASE/api/v1/projects/$PROJECT_ID"                -H "Authorization: Bearer $ACCESS"
curl -s -X PUT "$BASE/api/v1/projects/$PROJECT_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"name": "Mobile Banking v2"}'
curl -i -X DELETE "$BASE/api/v1/projects/$PROJECT_ID"      -H "Authorization: Bearer $ACCESS"   # 204
curl -i -X DELETE "$BASE/api/v1/organisations/$ORG_ID/members/<userId>" -H "Authorization: Bearer $ACCESS"  # 204
```

**2.6 Tạo custom role (→ 201)**

Khi 3 role dựng sẵn quá thô — ví dụ cần một người *chỉ* được bật/tắt flag, không được đụng environment:

```bash
curl -s -X POST $BASE/api/v1/organisations/$ORG_ID/roles \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Flag Toggler",
    "actions": ["FLAG_READ", "FLAG_STATE_UPDATE"]
  }'
```

`actions` không được rỗng, giá trị phải nằm trong enum `Action` (27 giá trị). Lưu `ROLE_ID`.
Quản lý custom role cần `ROLE_MANAGE` (ADMIN trở lên). List / sửa / xoá:

```bash
curl -s "$BASE/api/v1/organisations/$ORG_ID/roles"                -H "Authorization: Bearer $ACCESS"
curl -s -X PUT "$BASE/api/v1/organisations/$ORG_ID/roles/$ROLE_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"name": "Flag Toggler", "actions": ["FLAG_READ"]}'
curl -i -X DELETE "$BASE/api/v1/organisations/$ORG_ID/roles/$ROLE_ID" -H "Authorization: Bearer $ACCESS"  # 204
```

**2.7 Cấp quyền trên một project (grant)**

Nâng quyền một người trên **đúng một project**, không đụng tới các project khác. Truyền **đúng một** trong `role` hoặc `customRoleId`:

```bash
# grant bằng built-in role
curl -s -X POST $BASE/api/v1/projects/$PROJECT_ID/members \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"userId": "<userId>", "role": "ADMIN"}'

# hoặc grant bằng custom role
curl -s -X POST $BASE/api/v1/projects/$PROJECT_ID/members \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"userId\": \"<userId>\", \"customRoleId\": \"$ROLE_ID\"}"

curl -s "$BASE/api/v1/projects/$PROJECT_ID/members"                    -H "Authorization: Bearer $ACCESS"
curl -i -X DELETE "$BASE/api/v1/projects/$PROJECT_ID/members/<userId>" -H "Authorization: Bearer $ACCESS"  # 204
```

Endpoint là **upsert**: gọi lại cho cùng `userId` sẽ ghi đè grant cũ, không tạo bản thứ hai. Cần `GRANT_MANAGE` (ADMIN trở lên).

**2.8 Bảo vệ production: đặt loại môi trường + change window**

Hai thuộc tính này là đầu vào của luật production, đặt lúc tạo hoặc sửa environment (chi tiết ở luồng 3):

```bash
curl -s -X PUT "$BASE/api/v1/environments/$ENV_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"type": "PRODUCTION", "changeWindowStartHour": 9, "changeWindowEndHour": 17}'
```

`PUT` bình thường chỉ cần `ENV_UPDATE` (ADMIN), nhưng nếu request **thực sự làm đổi** `type` hoặc change window thì phải qua thêm `ENV_MANAGE_PROTECTION` — **chỉ OWNER** (`EnvironmentServiceImpl.java:107`). Nếu không, ADMIN chỉ cần hạ env xuống non-production là tự mở khoá cho mình. Hai field giờ phải đi cùng nhau (thiếu một ⇒ 400).

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| **Người ngoài org** | Login bằng `dev@aibles.com` (chưa được mời) rồi `GET /organisations/$ORG_ID` | **403** `"You are not a member of this organisation"` |
| **VIEWER thử ghi** | Mời `dev@aibles.com` làm VIEWER, dùng token của họ tạo flag | **403** `"Insufficient permissions for action: FLAG_CREATE"` |
| **ADMIN đụng production** | Token ADMIN gọi `PUT /flags/{id}/environments/{prodEnvId}` | **403** `"FLAG_STATE_UPDATE against a PRODUCTION environment requires elevated permission"` |
| **Archive cũng bị chặn** | Token ADMIN gọi `DELETE /flags/{id}` khi project có env PRODUCTION | 403 — archive nằm trong `PRODUCTION_ELEVATED`, không phải lối vòng |
| **Ngoài change window** | Đặt window `9→17`, dùng token OWNER bật flag prod lúc 20h | **403** `"Production changes are only allowed within the configured change window"` |
| **Grant có tác dụng** | Grant `ADMIN` trên `$PROJECT_ID` cho một VIEWER, dùng token họ tạo flag trong project đó | 201 — nhưng vẫn 403 ở project khác |
| **Grant không hạ cấp** | Grant `VIEWER` trên project cho một ADMIN của org | Vẫn ghi được — grant chỉ cộng quyền |
| **Mời role cao hơn mình** | Token ADMIN mời một người làm `OWNER` | 403 `"You cannot invite a member with a role higher than your own"` |
| **Xoá OWNER cuối** | Gỡ chính mình khỏi org khi là OWNER duy nhất | 403 `"Cannot remove the only OWNER of an organisation"` |
| Custom role rỗng action | `"actions": []` | 400 |
| Slug sai định dạng | `"slug": "Aibles Java"` | 400 |
| Slug trùng | tạo lại org cùng slug | 409 |
| Page quá lớn | `?size=5000` | 200 nhưng `size` trả về là 100 |

---

# Luồng 3 — Environment & API key: cấp chìa khoá cho SDK

### Vấn đề giải quyết

SDK chạy trong ứng dụng khách (backend service, mobile app) **không thể đăng nhập bằng email/password**. Nó cần một credential dài hạn, không gắn với người, và **tự nó xác định luôn là môi trường nào** — để cùng một dòng code không cần biết mình đang chạy dev hay prod.

Trước đây một environment chỉ có **đúng một** API key (`environments.api_key_hash`, NOT NULL UNIQUE). Điều đó kéo theo ba vấn đề thực tế:

1. **Rotate là hard cutover.** Ghi đè hash tại chỗ ⇒ mọi SDK client đang chạy bị văng ra ngay khi call trả về, cho tới khi từng client được deploy lại với key mới. Vì vậy team ngại rotate — ngược hẳn với mục đích của việc rotate key.
2. **Một key bị lộ thì phải thu hồi cả cụm.** Một key phục vụ mọi consumer, nên revoke key mà app mobile làm lộ cũng giết luôn batch job và backend đang dùng chung key đó.
3. **Key không bao giờ hết hạn.** Key cấp cho một đối tác tích hợp 2 tuần thì cứ sống mãi, hệ thống không có khái niệm "key này chỉ tạm thời".

Nên bây giờ key được tách thành entity riêng — bảng `environment_api_key` (migration `019`/`020`, thay cho cột `environments.api_key_hash` đã bị xoá) — để **một environment giữ được nhiều key cùng lúc**, mỗi key có vòng đời (hết hạn, thu hồi) riêng. "Một key = một môi trường" **không còn đúng nữa**: đúng phải là *nhiều key cùng xác thực cho một môi trường*, mỗi key ứng với một consumer (mobile app, batch job, backend...).

Vấn đề DB bị lộ vẫn được giữ nguyên cách xử lý cũ: key **không lưu plaintext** — chỉ lưu SHA-256 hash, y hệt cách xử lý password. Plaintext chỉ hiện **đúng một lần**, lúc tạo (`POST .../api-keys`) hoặc lúc rotate.

### Cơ chế

- `util/ApiKeyGenerator.java` — không đổi: `SecureRandom` 32 byte → hex 64 ký tự (256 bit entropy)
- `util/ApiKeyHasher.java` — không đổi: SHA-256, lookup O(1) khi SDK gọi
- `util/EnvironmentApiKeyFactory.mint(...)` — điểm khởi tạo **duy nhất** cho một key: sinh plaintext, hash nó, và cắt 8 ký tự đầu làm `key_prefix` (lưu kèm hash để phân biệt các key khi plaintext đã mất). Ba nơi gọi tới: tạo environment (`EnvironmentServiceImpl.create()`), clone environment (`EnvironmentTransferServiceImpl.clone()` — clone **luôn** mint key riêng, không copy key của nguồn), và API tạo key mới.
- Một key **active** khi và chỉ khi `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now)` — đúng một chỗ định nghĩa (`EnvironmentApiKey.isActive(Clock)`), dùng cho cả xác thực SDK, đếm hạn mức, và endpoint rotate cũ có key duy nhất hay không.
- Hạn mức: **tối đa 10 key active** mỗi environment (`EnvironmentApiKeyServiceImpl.MAX_ACTIVE_KEYS_PER_ENVIRONMENT`). Rotate **không** bị chặn bởi hạn mức này — trong lúc grace period, environment tạm thời có nhiều hơn 10 key active, việc đó là cố ý.
- `EnvironmentApiKeyServiceImpl` — create/list/revoke/rotate; mỗi thao tác qua `PermissionService.check(...)` rồi ghi audit (`before`/`after` cố định `null` — ledger chỉ ghi *có sự kiện xảy ra*, không ghi key).
- `ApiKeyAuthenticationFilter` — principal giờ là `EnvironmentApiKey` (không còn là `Environment` trực tiếp); lấy `Environment` qua `((EnvironmentApiKey) auth.getPrincipal()).getEnvironment()`. Ba lý do 401 tách biệt (xem bảng lỗi bên dưới) nhưng dùng chung 1 counter, để không biến metric thành "oracle" tiết lộ key nào tồn tại.
- Endpoint environment-level cũ (`POST /environments/{id}/api-key/rotate`) **vẫn còn** — dùng khi environment chỉ có đúng 1 key active; nếu 0 hoặc ≥2 key active thì trả **409**, chỉ tên endpoint mới thay vì tự đoán "cái key" là key nào.
- Rotate mới (endpoint theo key, `POST .../api-keys/{keyId}/rotate`) nhận `graceHours` (mặc định `0`, tối đa `720` = 30 ngày): key mới mang cùng `name` với key cũ; key cũ được set `expires_at = now + graceHours` thay vì bị revoke ngay — **đây là trọng tâm của tính năng**, cho phép redeploy một fleet SDK dần dần mà không downtime. `graceHours=0` thì key cũ bị revoke ngay lập tức, giống hệt hành vi cutover cũ.

### Request

**3.1 Tạo environment (→ 201 — COPY NGAY `apiKey`, đây chính là key đầu tiên, tên `default`)**

```bash
curl -s -X POST $BASE/api/v1/environments \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{
    \"projectId\": \"$PROJECT_ID\",
    \"name\": \"development\",
    \"description\": \"Moi truong dev\"
  }"
```

```json
{
  "id": "8c7d...",
  "name": "development",
  "description": "Moi truong dev",
  "projectId": "...",
  "apiKey": "4b1e9c...64 ký tự hex   ← chỉ hiện lần này",
  "createdAt": "2026-08-16T10:12:33"
}
```

Lưu `ENV_ID` và `API_KEY`. Tạo thêm môi trường `production` để test tách biệt:

```bash
curl -s -X POST $BASE/api/v1/environments \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"projectId\": \"$PROJECT_ID\", \"name\": \"production\"}"
```

**3.2 Tạo thêm key thứ 2 cho cùng environment (→ 201 — mỗi consumer một key)**

```bash
curl -s -X POST $BASE/api/v1/environments/$ENV_ID/api-keys \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"name": "mobile-app", "expiresAt": "2026-12-31T00:00:00"}'
```

```json
{
  "key": {
    "id": "1a2b...",
    "environmentId": "8c7d...",
    "name": "mobile-app",
    "keyPrefix": "9f3a7c21",
    "expiresAt": "2026-12-31T00:00:00",
    "revokedAt": null,
    "lastUsedAt": null,
    "createdBy": "...",
    "createdAt": "2026-09-05T10:00:00",
    "active": true
  },
  "apiKey": "9f3a7c21...64 ký tự hex   ← chỉ hiện lần này"
}
```

`name` **không cần unique** — trong lúc grace period, key cũ và key mới của cùng một lần rotate cùng mang một tên; phân biệt bằng `keyPrefix` + `createdAt`. `expiresAt` là tuỳ chọn (phải ở tương lai); bỏ trống nghĩa là không bao giờ hết hạn.

**3.3 Danh sách key của environment (→ 200, phân trang, KHÔNG có plaintext lẫn hash)**

```bash
curl -s "$BASE/api/v1/environments/$ENV_ID/api-keys?page=0&size=20" -H "Authorization: Bearer $ACCESS"
```

**3.4 Thu hồi (revoke) một key riêng lẻ (→ 204, soft — chỉ set `revokedAt`, không xoá row)**

```bash
curl -i -X DELETE "$BASE/api/v1/environments/$ENV_ID/api-keys/$KEY_ID" \
  -H "Authorization: Bearer $ACCESS"
```

Chỉ key này chết; các key khác của cùng environment **không bị ảnh hưởng** — đây là điểm khác biệt lớn nhất so với model một-key-một-môi-trường cũ.

**3.5 Rotate một key cụ thể, có grace period (→ 200 + `apiKey` mới)**

```bash
curl -s -X POST "$BASE/api/v1/environments/$ENV_ID/api-keys/$KEY_ID/rotate" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"graceHours": 24}'
```

Trong 24 giờ tiếp theo, **cả key cũ lẫn key mới đều xác thực được** — đủ thời gian để redeploy fleet SDK. Muốn cutover ngay như trước thì gọi với `{"graceHours": 0}` hoặc bỏ field (mặc định là `0`).

**3.6 Rotate kiểu cũ — chỉ dùng được khi environment có đúng 1 key active (→ 200, hoặc 409)**

```bash
curl -s -X POST $BASE/api/v1/environments/$ENV_ID/api-key/rotate \
  -H "Authorization: Bearer $ACCESS"
```

**3.7 Các endpoint còn lại**

```bash
curl -s "$BASE/api/v1/environments?projectId=$PROJECT_ID" -H "Authorization: Bearer $ACCESS"
curl -s "$BASE/api/v1/environments/$ENV_ID"               -H "Authorization: Bearer $ACCESS"
curl -s -X PUT "$BASE/api/v1/environments/$ENV_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"description": "Mo ta moi"}'
curl -i -X DELETE "$BASE/api/v1/environments/$ENV_ID"     -H "Authorization: Bearer $ACCESS"   # 204, cần OWNER
```

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| **Key không lấy lại được** | `GET /environments/$ENV_ID` hoặc `GET .../api-keys` | response **không có** field `apiKey` (list còn không có cả `keyHash`) |
| **Revoke làm chết đúng 1 key** | Revoke key A xong gọi SDK bằng key A | 401 `"API key has been revoked"` |
| **Revoke không ảnh hưởng key khác** | Sau khi revoke key A, gọi SDK bằng key B (cùng environment) | vẫn 200 |
| **Key hết hạn** | Gọi SDK bằng key có `expiresAt` đã qua | 401 `"API key has expired"` (biên đóng: đúng thời điểm `expiresAt` cũng tính là hết hạn) |
| **Key không tồn tại** | Gọi SDK bằng key ngẫu nhiên | 401 `"Invalid API key"` (không lộ thông tin key nào có thật) |
| **Rotate làm chết key cũ ngay (graceHours=0)** | Rotate xong (không truyền `graceHours`), gọi SDK bằng key cũ | 401 `"API key has been revoked"` |
| **Rotate với grace period** | Rotate `graceHours=24`, gọi SDK bằng cả key cũ lẫn key mới | cả hai đều 200 cho tới khi hết 24h |
| Vượt hạn mức 10 key active | Tạo key thứ 11 khi 10 key kia còn active | 409 `"Environment has reached the maximum of 10 active API keys"` |
| Revoke key đã revoke | Gọi DELETE 2 lần cùng 1 key | lần 2 → 409 `"API key is already revoked"` |
| Key thuộc environment khác | Gọi `DELETE .../environments/{envA}/api-keys/{keyOfEnvB}` | 404 (không lộ là key có tồn tại ở nơi khác) |
| Rotate kiểu cũ khi có 0 hoặc ≥2 key active | Gọi `POST .../api-key/rotate` lúc environment có 2 key | 409, message chỉ sang endpoint theo key |
| Trùng tên env | tạo lại `development` trong cùng project | 409 |
| ADMIN xoá environment | dùng token role ADMIN gọi DELETE | 403 (chỉ OWNER được xoá) |
| ADMIN tạo/revoke key trên PRODUCTION | dùng token role ADMIN gọi `POST`/`DELETE .../api-keys` trên env `PRODUCTION` | 403 — cần OWNER (`ENV_KEY_CREATE_PRODUCTION`/`ENV_KEY_REVOKE_PRODUCTION`) |
| OWNER revoke key PRODUCTION ngoài change window | đặt window `9–17`, gọi `DELETE` lúc 3h sáng | **vẫn 200/204** — revoke chỉ *giảm* quyền truy cập nên được miễn luật change window (xem Luồng 2, mục change window) |
| OWNER rotate key PRODUCTION ngoài change window | đặt window `9–17`, gọi rotate lúc 3h sáng | 403 — rotate **không** được miễn, vẫn phải trong window |

---

# Luồng 4 — Feature flag: định nghĩa một lần, trạng thái theo từng môi trường

### Vấn đề giải quyết

Đây là lõi của sản phẩm. Vấn đề gốc: **muốn deploy code mới mà chưa bật tính năng**, và bật/tắt được **mà không cần deploy lại**.

Mô hình tách đôi cố ý:

- `FeatureFlag` — *định nghĩa* flag: tên, key, kiểu giá trị. Thuộc về **project**, một bản duy nhất.
- `FlagEnvironmentState` — *trạng thái* flag: `enabled`, `value`, `rolloutPercent`. Một dòng cho **mỗi environment**.

Nhờ vậy `checkout-v2` bật ở dev, tắt ở prod, mà vẫn là **một** flag. Nếu gộp làm một bảng thì mỗi môi trường sẽ có một flag riêng và không ai biết chúng là cùng một thứ.

`key` là **bất biến** — SDK đã hard-code chuỗi này trong code khách hàng. Cho sửa key nghĩa là cho phép làm chết ứng dụng đang chạy từ xa. `update()` cố tình bỏ qua field `key` (`FeatureFlagServiceImpl.java:119`).

Xoá flag dùng **archive** (soft delete) chứ không xoá cứng: flag đã tắt vẫn cần trong lịch sử audit, và xoá cứng sẽ làm SDK đang gọi nó chết đột ngột. Archive rồi vẫn `unarchive` lại được.

### Cơ chế

- `FeatureFlagServiceImpl.java:74` — tạo flag ⇒ tự sinh `FlagEnvironmentState` (`enabled=false`) cho **mọi** environment của project
- `updateState()` — nơi bật/tắt thật sự, đồng thời: ghi audit, phát `FlagStateChangedEvent` → Slack, tăng metrics
- Archive = `archived=true`, và `EvaluationServiceImpl` lọc bỏ flag archived ⇒ SDK ngừng thấy ngay

### Request

**4.1 Tạo flag (→ 201)**

```bash
curl -s -X POST $BASE/api/v1/flags \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{
    \"projectId\": \"$PROJECT_ID\",
    \"name\": \"Checkout v2\",
    \"key\": \"checkout-v2\",
    \"description\": \"Luong thanh toan moi\",
    \"valueType\": \"BOOLEAN\"
  }"
```

`key` phải khớp `^[a-z0-9-_]+$`. `valueType` ∈ `BOOLEAN` | `STRING` | `INTEGER` | `JSON`. Lưu `FLAG_ID`.

Tạo thêm một flag kiểu JSON để thấy `value` dùng làm gì:

```bash
curl -s -X POST $BASE/api/v1/flags \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{
    \"projectId\": \"$PROJECT_ID\",
    \"name\": \"Banner config\",
    \"key\": \"home_banner_config\",
    \"valueType\": \"JSON\"
  }"
```

**4.2 Bật flag ở một môi trường (→ 200) — đây là request quan trọng nhất**

```bash
curl -s -X PUT "$BASE/api/v1/flags/$FLAG_ID/environments/$ENV_ID" \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{
    "enabled": true,
    "value": "true",
    "rolloutPercent": 100
  }'
```

```json
{
  "flagId": "...",
  "environmentId": "...",
  "enabled": true,
  "value": "true",
  "rolloutPercent": 100
}
```

Với flag JSON, `value` là chuỗi chứa JSON:

```bash
curl -s -X PUT "$BASE/api/v1/flags/<flagId của home_banner_config>/environments/$ENV_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"enabled": true, "value": "{\"title\":\"Sale 50%\",\"color\":\"red\"}", "rolloutPercent": 100}'
```

**4.3 Xem trạng thái của flag ở một môi trường**

```bash
curl -s "$BASE/api/v1/flags/$FLAG_ID/environments/$ENV_ID" -H "Authorization: Bearer $ACCESS"
```

**4.4 Sửa / archive / unarchive / list**

```bash
# Sửa metadata (key bị bỏ qua dù có gửi)
curl -s -X PUT "$BASE/api/v1/flags/$FLAG_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"name": "Checkout v2 (beta)", "description": "Mo ta moi"}'

curl -i -X DELETE "$BASE/api/v1/flags/$FLAG_ID"           -H "Authorization: Bearer $ACCESS"  # archive, 204
curl -i -X POST "$BASE/api/v1/flags/$FLAG_ID/unarchive"   -H "Authorization: Bearer $ACCESS"  # 204

curl -s "$BASE/api/v1/flags?projectId=$PROJECT_ID"          -H "Authorization: Bearer $ACCESS"  # chỉ flag còn sống
curl -s "$BASE/api/v1/flags/archived?projectId=$PROJECT_ID" -H "Authorization: Bearer $ACCESS"  # chỉ flag đã archive
curl -s "$BASE/api/v1/flags/$FLAG_ID"                       -H "Authorization: Bearer $ACCESS"
```

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| **key bất biến** | `PUT /flags/$FLAG_ID` với body `{"name":"X","key":"hacked"}` | 200 nhưng `key` trong response **vẫn là** `checkout-v2` |
| **Tách biệt môi trường** | Bật flag ở `development`, gọi SDK bằng key của `production` | prod vẫn `enabled: false` |
| **Environment tạo sau không có state** | Tạo env `staging` **sau** khi đã có flag, rồi `GET /flags/$FLAG_ID/environments/<staging>` | 404 `"Flag state not found for this environment"` — đây là hành vi hiện tại, không phải bug ngẫu nhiên |
| Key trùng trong project | tạo lại flag `checkout-v2` | 409 |
| Key sai định dạng | `"key": "Checkout V2"` | 400 |
| `rolloutPercent` ngoài khoảng | `"rolloutPercent": 150` | 400 (`@Min(0) @Max(100)`) |
| Thiếu `enabled` | `PUT state` với body `{"value":"true"}` | 400 (`@NotNull`) |

---

# Luồng 5 — SDK evaluation: ứng dụng khách đọc flag

### Vấn đề giải quyết

Ứng dụng khách cần biết *bây giờ flag nào đang bật cho tôi*, với ràng buộc: gọi rất nhiều lần, phải nhanh, và **không được thấy dữ liệu của môi trường khác**.

Giải pháp: một security chain riêng biệt. API key không chỉ để xác thực — nó **chính là** cách hệ thống biết đang phục vụ môi trường nào. Controller không cần truy vấn thêm gì: principal là `EnvironmentApiKey` (Luồng 3 — một trong nhiều key của environment), và `Environment` lấy ra qua `getEnvironment()` trên chính principal đó, không cần query DB thêm lần nữa.

Vấn đề thứ hai — **percentage rollout**: muốn mở tính năng cho 10% người dùng để quan sát trước khi mở toàn bộ. Nhưng phải là *cùng 10% người đó* ở mọi lần gọi, nếu không user sẽ thấy giao diện nhấp nháy giữa cũ và mới. Giải pháp là hash tất định thay vì random: `MurmurHash3(identifier + ":" + flagKey) % 100 < rolloutPercent`. Không lưu state, không cần DB, cùng input luôn ra cùng kết quả (`util/RolloutEvaluator.java:13`).

Hash có kèm `flagKey` là cố ý: nếu chỉ hash `identifier`, cùng một nhóm người sẽ luôn trúng ở *mọi* flag 10% — người xui thì xui hết.

### Cơ chế

- `security/ApiKeyAuthenticationFilter.java` — đọc `X-Environment-Key` → hash → tra `EnvironmentApiKey` (revoked/expired thì 401 riêng biệt, xem Luồng 3) → set principal; đồng thời cập nhật `last_used_at` **của chính key đó** (chặn ghi DB mỗi request, chỉ ghi 5 phút/lần)
- `EvaluationServiceImpl.java` — load state active của env, lọc flag archived, chạy rollout
- **`enabled=false` ⇒ `value` trả về `null`** — client không đọc nhầm giá trị của tính năng đang tắt
- Rate limit riêng: 300 req/phút **theo environment** (không theo IP, vì một server backend có thể gọi rất nhiều từ một IP)

### Request

**5.1 Lấy toàn bộ flag của môi trường**

```bash
curl -s $BASE/api/v1/sdk/flags -H "X-Environment-Key: $API_KEY"
```

```json
[
  { "flagKey": "checkout-v2",        "enabled": true, "value": "true", "valueType": "BOOLEAN", "rolloutPercent": 100 },
  { "flagKey": "home_banner_config", "enabled": true, "value": "{\"title\":\"Sale 50%\"}", "valueType": "JSON", "rolloutPercent": 100 }
]
```

**5.2 Lấy một flag theo key**

```bash
curl -s $BASE/api/v1/sdk/flags/checkout-v2 -H "X-Environment-Key: $API_KEY"
```

**5.3 Có `identifier` — test percentage rollout**

Đặt rollout 10%:

```bash
curl -s -X PUT "$BASE/api/v1/flags/$FLAG_ID/environments/$ENV_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"enabled": true, "value": "true", "rolloutPercent": 10}'
```

Rồi gọi với nhiều user khác nhau:

```bash
for u in user-001 user-002 user-003 user-004 user-005 user-006 user-007 user-008 user-009 user-010; do
  printf "%s -> " $u
  curl -s "$BASE/api/v1/sdk/flags/checkout-v2?identifier=$u" -H "X-Environment-Key: $API_KEY"
  echo
done
```

Kết quả mong đợi: khoảng 1/10 user có `enabled: true`, phần còn lại `enabled: false` và `value: null`. **Chạy lại vòng lặp lần nữa — kết quả phải giống hệt lần đầu.** Đó là điểm mấu chốt của rollout tất định.

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| Thiếu header | gọi SDK không có `X-Environment-Key` | **401** `"Missing X-Environment-Key header"` |
| Key sai | `X-Environment-Key: sai-be-bet` | 401 `"Invalid API key"` |
| **Dùng JWT nhầm chỗ** | gọi SDK bằng `Authorization: Bearer $ACCESS` | 401 — hai chain hoàn toàn tách biệt |
| **Không thấy flag đã archive** | archive flag rồi gọi `GET /sdk/flags` | flag biến mất khỏi danh sách; gọi theo key → 404 |
| **Tắt flag thì mất value** | set `enabled: false` rồi gọi SDK | `enabled: false`, `value: null` |
| Không truyền `identifier` với rollout < 100 | `GET /sdk/flags/checkout-v2` (không có `?identifier=`) | `enabled: true` — không định danh được thì không chia nhóm được, hệ thống mặc định cho qua |
| Flag không tồn tại | `GET /sdk/flags/khong-co-that` | 404 |
| Rate limit SDK | gọi > 300 lần/phút bằng cùng một key | 429 |

---

# Luồng 6 — Audit log: ai đã đổi cái gì, lúc nào

### Vấn đề giải quyết

Khi một flag bị tắt ngoài ý muốn và production sập, câu hỏi đầu tiên là *ai vừa đổi gì*. Không có audit thì không trả lời được — mà đây lại đúng là loại hệ thống mà một cú click đổi hành vi của toàn bộ production ngay lập tức.

Hai yêu cầu khó:

1. **Không được có audit ma, cũng không được sót audit.** Nếu ghi audit ở transaction riêng, thao tác rollback vẫn để lại audit row (audit ma), hoặc thao tác thành công nhưng audit ghi lỗi (sót). Giải pháp: `AuditService.record()` chạy **trong chính transaction của thao tác** ⇒ commit cùng nhau hoặc chết cùng nhau.
2. **Không được rò rỉ bí mật vào log.** Audit lưu `before`/`after` dạng JSON. Rotate API key vì thế cố tình ghi `before = after = null` — chỉ ghi *đã có sự kiện rotate*, không bao giờ ghi key.

Bảng này **append-only**: chỉ `INSERT`, không bao giờ `UPDATE`/`DELETE`.

### Cơ chế

- `service/impl/AuditService.java` — `record()` ghi, `list()` đọc (yêu cầu VIEWER+)
- Được gọi từ tất cả service impl có thao tác ghi
- Đọc: `GET /api/v1/organisations/{orgId}/audit-log`, phân trang, **mới nhất trước**

### Request

```bash
curl -s "$BASE/api/v1/organisations/$ORG_ID/audit-log?page=0&size=20" \
  -H "Authorization: Bearer $ACCESS"
```

```json
{
  "content": [
    {
      "id": "...",
      "actorUserId": "3f1b...",
      "orgId": "...",
      "action": "CHANGE_STATE",
      "entityType": "FLAG_STATE",
      "entityId": "...",
      "beforeState": { "enabled": false, "value": null,   "rolloutPercent": 0 },
      "afterState":  { "enabled": true,  "value": "true", "rolloutPercent": 100 },
      "createdAt": "2026-08-16T10:20:44"
    }
  ],
  "page": 0, "size": 20, "totalElements": 12, "totalPages": 1
}
```

`action` ∈ `CREATE` `UPDATE` `DELETE` `ARCHIVE` `UNARCHIVE` `INVITE_MEMBER` `REMOVE_MEMBER` `ROTATE_API_KEY` `CHANGE_STATE`
`entityType` ∈ `ORGANIZATION` `PROJECT` `ENVIRONMENT` `FEATURE_FLAG` `FLAG_STATE` `MEMBER` `API_KEY`

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| **Không rò rỉ key** | Rotate API key rồi xem audit log | có row `ROTATE_API_KEY` nhưng `beforeState` và `afterState` đều `null` |
| **Không có audit ma** | Thử tạo flag trùng key (409) rồi xem audit | **không** có row nào được thêm |
| Truy vết đổi trạng thái | Bật/tắt flag vài lần rồi xem log | mỗi lần một row `CHANGE_STATE` với before/after đầy đủ |
| Người ngoài org đọc log | dùng token của user không thuộc org | 403 |

---

# Luồng 7 — Vận hành: thông báo, metrics, rate limit, log

### Vấn đề giải quyết

**Slack notification** — bật/tắt flag production là hành động ảnh hưởng toàn bộ người dùng nhưng lại chỉ mất một cú click. Team cần biết ngay mà không phải ngồi canh audit log. Việc gửi Slack **không được làm chậm hay làm hỏng** thao tác chính ⇒ dùng `ApplicationEvent` + `@Async`: Slack sập thì flag vẫn đổi bình thường.

**Rate limit** — hai loại lạm dụng khác nhau nên có hai chính sách khác nhau: `/auth/**` bị dò mật khẩu ⇒ giới hạn **theo IP**; `/sdk/**` bị gọi quá tay ⇒ giới hạn **theo API key** (một backend gọi từ một IP là bình thường, không thể phạt theo IP).

**Metrics & correlation id** — trả lời "hệ thống có khoẻ không" và "request lỗi của khách hàng là request nào trong log".

### Cơ chế

- `notification/SlackEventListener.java` — `@Async` nghe `FlagStateChangedEvent`, `FlagArchivedEvent`, `ApiKeyRotatedEvent`
- `security/ratelimit/` — token bucket, cấu hình qua `app.rate-limit.*`, vượt ngưỡng → 429 + `Retry-After`
- `logging/RequestCorrelationFilter.java` — gắn `requestId` (và `envId` cho SDK) vào MDC, clear trong `finally`
- `metrics/FeatureFlagMetrics.java` — đếm auth failure, thay đổi flag, đo latency evaluation
- `SecurityConfig.java:62` — chain actuator: `/actuator/health` public, còn lại cần HTTP Basic role `METRICS`

### Cấu hình mặc định (`application.properties`)

```properties
app.rate-limit.enabled=true
app.rate-limit.auth.capacity=10          # 10 req/phút theo IP
app.rate-limit.auth.refill-period=1m
app.rate-limit.sdk.capacity=300          # 300 req/phút theo API key
app.rate-limit.sdk.refill-period=1m
app.slack.enabled=false                  # bật kèm SLACK_WEBHOOK_URL
app.metrics.password=${APP_METRICS_PASSWORD:}
```

### Request

**7.1 Health check (không cần xác thực)**

```bash
curl -s $BASE/actuator/health
```

**7.2 Prometheus metrics (cần Basic auth)**

```bash
export APP_METRICS_PASSWORD=scrape-me      # đặt trước khi khởi động app
curl -s -u metrics:scrape-me $BASE/actuator/prometheus | grep feature_flag
```

**7.3 Test rate limit auth**

```bash
for i in $(seq 1 12); do
  printf "%02d -> " $i
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"owner@aibles.com","password":"sai-mat-khau"}'
done
```

Mong đợi: 10 request đầu trả 401, từ request 11 trả **429**.

**7.4 Bật Slack**

```bash
export SLACK_WEBHOOK_URL='https://hooks.slack.com/services/XXX/YYY/ZZZ'
# thêm app.slack.enabled=true rồi khởi động lại; sau đó bật/tắt một flag bất kỳ
```

### Case lỗi nên test

| Test | Cách làm | Kết quả đúng |
|---|---|---|
| **Không để lộ metrics** | `GET /actuator/prometheus` không có Basic auth | 401 |
| **Chặn bypass khi thiếu mật khẩu** | Không set `APP_METRICS_PASSWORD`, thử user `metrics` mật khẩu rỗng | 401 — tài khoản được tạo ở trạng thái `disabled`, không phải "mật khẩu rỗng khớp mật khẩu rỗng" |
| **Health luôn mở** | `GET /actuator/health` không auth | 200 |
| Slack sập không ảnh hưởng flag | set webhook URL sai rồi bật flag | flag vẫn đổi thành công (200) |

---

# Kịch bản smoke test end-to-end

Copy nguyên khối, chạy trong Bash (cần `jq`):

```bash
BASE=http://localhost:8081
EMAIL="owner+$(date +%s)@aibles.com"
SLUG="org-$(date +%s)"

# 1. Đăng ký + đăng nhập
curl -s -X POST $BASE/api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Password123!\",\"firstName\":\"T\",\"lastName\":\"B\"}"

AUTH=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Password123!\"}")
ACCESS=$(echo $AUTH | jq -r .accessToken)
REFRESH=$(echo $AUTH | jq -r .refreshToken)

# 2. Org → Project
ORG_ID=$(curl -s -X POST $BASE/api/v1/organisations -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d "{\"name\":\"Aibles\",\"slug\":\"$SLUG\"}" | jq -r .id)

PROJECT_ID=$(curl -s -X POST $BASE/api/v1/projects -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{\"organisationId\":\"$ORG_ID\",\"name\":\"Mobile Banking\"}" | jq -r .id)

# 3. Environment TRƯỚC (lấy apiKey — chỉ hiện một lần)
ENV=$(curl -s -X POST $BASE/api/v1/environments -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$PROJECT_ID\",\"name\":\"development\"}")
ENV_ID=$(echo $ENV | jq -r .id)
API_KEY=$(echo $ENV | jq -r .apiKey)

# 4. Flag SAU (state tự sinh cho environment ở trên)
FLAG_ID=$(curl -s -X POST $BASE/api/v1/flags -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$PROJECT_ID\",\"name\":\"Checkout v2\",\"key\":\"checkout-v2\",\"valueType\":\"BOOLEAN\"}" \
  | jq -r .id)

# 5. SDK đọc khi flag còn tắt
echo "--- truoc khi bat:"
curl -s $BASE/api/v1/sdk/flags -H "X-Environment-Key: $API_KEY" | jq

# 6. Bật flag
curl -s -X PUT "$BASE/api/v1/flags/$FLAG_ID/environments/$ENV_ID" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"enabled":true,"value":"true","rolloutPercent":100}' | jq

# 7. SDK đọc lại
echo "--- sau khi bat:"
curl -s $BASE/api/v1/sdk/flags -H "X-Environment-Key: $API_KEY" | jq

# 8. Audit log
echo "--- audit:"
curl -s "$BASE/api/v1/organisations/$ORG_ID/audit-log?size=5" -H "Authorization: Bearer $ACCESS" \
  | jq '.content[] | {action, entityType, afterState}'

# 9. Chứng minh reuse detection: refresh 2 lần cùng 1 token
curl -s -o /dev/null -w "refresh lan 1: %{http_code}\n" -X POST $BASE/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}"
curl -s -o /dev/null -w "refresh lan 2: %{http_code}\n" -X POST $BASE/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}"
```

Kết quả mong đợi ở bước cuối: `refresh lan 1: 200`, `refresh lan 2: 401`.

---

## Bảng tra endpoint nhanh

Cột **Quyền** ghi `Action` mà PDP kiểm tra (`PermissionService.check`), kèm role dựng sẵn tối thiểu có action đó. Chỗ đánh `†` là action nằm trong `PRODUCTION_ELEVATED`: nếu môi trường đích là `PRODUCTION` thì yêu cầu nhảy lên **OWNER** và phải nằm trong change window. Chỗ đánh `‡` vẫn đi qua adapter `requireRole*` cũ nên **không** chịu hai luật đó. Chỗ đánh `§` là ngoại lệ **duy nhất** của luật change window: vẫn cần OWNER trên PRODUCTION (`†`), nhưng được miễn khung giờ — xem `PermissionService.WINDOW_EXEMPT` và mục change window ở Luồng 2.

| Method | Endpoint | Auth | Quyền | Status |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | — | — | 201 |
| POST | `/api/v1/auth/login` | — | — | 200 |
| POST | `/api/v1/auth/refresh` | — | — | 200 |
| POST | `/api/v1/auth/logout` | — | — | 204 |
| POST | `/api/v1/organisations` | JWT | — (ai cũng tạo được, thành OWNER) | 201 |
| GET | `/api/v1/organisations` | JWT | — (chỉ org mình là member) | 200 |
| GET | `/api/v1/organisations/{orgId}` | JWT | là member | 200 |
| PUT | `/api/v1/organisations/{orgId}` | JWT | `ORG_UPDATE` — ADMIN | 200 |
| DELETE | `/api/v1/organisations/{orgId}` | JWT | `ORG_DELETE` — OWNER | 204 |
| GET | `/api/v1/organisations/{orgId}/members` | JWT | là member | 200 |
| POST | `/api/v1/organisations/{orgId}/members` | JWT | `MEMBER_INVITE` — ADMIN | 201 |
| DELETE | `/api/v1/organisations/{orgId}/members/{userId}` | JWT | `MEMBER_MANAGE` — ADMIN | 204 |
| GET | `/api/v1/organisations/{orgId}/audit-log` | JWT | `AUDIT_READ` — VIEWER | 200 |
| GET/POST | `/api/v1/organisations/{orgId}/roles` | JWT | `ROLE_MANAGE` — ADMIN | 200 / 201 |
| PUT/DELETE | `/api/v1/organisations/{orgId}/roles/{roleId}` | JWT | `ROLE_MANAGE` — ADMIN | 200 / 204 |
| POST | `/api/v1/projects` | JWT | `PROJECT_CREATE` — ADMIN | 201 |
| GET | `/api/v1/projects?organisationId=` | JWT | `PROJECT_READ` — VIEWER | 200 |
| GET | `/api/v1/projects/{projectId}` | JWT | `PROJECT_READ` — VIEWER | 200 |
| PUT | `/api/v1/projects/{projectId}` | JWT | `PROJECT_UPDATE` — ADMIN | 200 |
| DELETE | `/api/v1/projects/{projectId}` | JWT | `PROJECT_DELETE` — OWNER | 204 |
| GET/POST | `/api/v1/projects/{projectId}/members` | JWT | `GRANT_MANAGE` — ADMIN | 200 |
| DELETE | `/api/v1/projects/{projectId}/members/{userId}` | JWT | `GRANT_MANAGE` — ADMIN | 204 |
| POST | `/api/v1/environments` | JWT | `ENV_CREATE` — ADMIN | 201 + `apiKey` |
| GET | `/api/v1/environments?projectId=` | JWT | `ENV_READ` — VIEWER | 200 |
| GET | `/api/v1/environments/{envId}` | JWT | `ENV_READ` — VIEWER | 200 |
| PUT | `/api/v1/environments/{envId}` | JWT | `ENV_UPDATE` — ADMIN; đổi `type`/change window cần `ENV_MANAGE_PROTECTION` — OWNER | 200 |
| DELETE | `/api/v1/environments/{envId}` | JWT | `ENV_DELETE` — OWNER † | 204 |
| POST | `/api/v1/environments/{envId}/api-key/rotate` | JWT | `ENV_ROTATE_KEY` — ADMIN † | 200 + `apiKey`, hoặc **409** nếu env có 0 hoặc ≥2 key active |
| POST | `/api/v1/environments/{envId}/api-keys` | JWT | `ENV_KEY_CREATE` — ADMIN † | 201 + `apiKey` (chỉ hiện lần này) |
| GET | `/api/v1/environments/{envId}/api-keys` | JWT | `ENV_READ` — VIEWER | 200, phân trang, không có `apiKey`/hash |
| DELETE | `/api/v1/environments/{envId}/api-keys/{keyId}` | JWT | `ENV_KEY_REVOKE` — ADMIN † § | 204 (soft — set `revokedAt`) |
| POST | `/api/v1/environments/{envId}/api-keys/{keyId}/rotate` | JWT | `ENV_ROTATE_KEY` — ADMIN † | 200 + `apiKey` mới; `graceHours` giữ key cũ sống thêm |
| POST | `/api/v1/environments/{envId}/clone` | JWT | ADMIN ‡ | 201 + `apiKey` |
| GET | `/api/v1/environments/{envId}/export` | JWT | ADMIN ‡ | 200 |
| POST | `/api/v1/environments/{envId}/import` | JWT | `FLAG_CREATE` + `FLAG_STATE_UPDATE` — ADMIN † | 200 |
| POST | `/api/v1/flags` | JWT | `FLAG_CREATE` — ADMIN | 201 |
| GET | `/api/v1/flags?projectId=` | JWT | `FLAG_READ` — VIEWER | 200 |
| GET | `/api/v1/flags/archived?projectId=` | JWT | `FLAG_READ` — VIEWER | 200 |
| GET | `/api/v1/flags/{flagId}` | JWT | `FLAG_READ` — VIEWER | 200 |
| PUT | `/api/v1/flags/{flagId}` | JWT | `FLAG_UPDATE` — ADMIN | 200 |
| DELETE | `/api/v1/flags/{flagId}` | JWT | `FLAG_ARCHIVE` — ADMIN † | 204 (archive) |
| POST | `/api/v1/flags/{flagId}/unarchive` | JWT | `FLAG_ARCHIVE` — ADMIN † | 204 |
| GET | `/api/v1/flags/{flagId}/environments/{envId}` | JWT | `FLAG_READ` — VIEWER | 200 |
| PUT | `/api/v1/flags/{flagId}/environments/{envId}` | JWT | `FLAG_STATE_UPDATE` — ADMIN † | 200 |
| GET | `/api/v1/flag-hygiene?projectId=` | JWT | VIEWER ‡ | 200 |
| POST/GET | `/api/v1/webhooks` | JWT | ADMIN ‡ | 201 / 200 |
| GET/PUT/DELETE | `/api/v1/webhooks/{webhookId}` | JWT | ADMIN ‡ | 200 / 200 / 204 |
| POST | `/api/v1/webhooks/{webhookId}/secret/rotate` | JWT | ADMIN ‡ | 200 |
| GET | `/api/v1/webhooks/{webhookId}/deliveries` | JWT | ADMIN ‡ | 200 |
| GET | `/api/v1/sdk/flags` | API key | — | 200 |
| GET | `/api/v1/sdk/flags/{flagKey}` | API key | — | 200 |
| GET | `/actuator/health` | — | — | 200 |
| GET | `/actuator/prometheus` | Basic | METRICS | 200 |
