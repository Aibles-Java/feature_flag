# Luồng demo API — Feature Flag Platform

Kịch bản demo end-to-end: đăng ký → tạo org/project/environment → tạo flag → bật flag theo môi trường → SDK đọc flag → clone/export/import → audit log.

- File chạy được ngay: [`api-demo-flow.http`](./api-demo-flow.http) (IntelliJ HTTP Client — tự động lưu token/id giữa các request).
- File này: bản đọc/copy tay, dùng cho **Swagger UI**, **Postman** hoặc `curl.exe`.

## Chuẩn bị

```bash
docker compose up -d        # PostgreSQL
./mvnw spring-boot:run      # app chạy ở cổng 8081
```

| | |
|---|---|
| Base URL | `http://localhost:8081` |
| Swagger UI | http://localhost:8081/swagger-ui.html |
| OpenAPI JSON | http://localhost:8081/api-docs |

Hai loại xác thực, **không dùng lẫn nhau**:

| Nhóm endpoint | Header |
|---|---|
| Admin API (`/api/v1/organisations`, `/projects`, `/environments`, `/flags`) | `Authorization: Bearer <accessToken>` |
| SDK API (`/api/v1/sdk/**`) | `X-Environment-Key: <apiKey của environment>` |
| Auth (`/api/v1/auth/**`) | không cần header |

## 3 điều dễ sai nhất

1. **Tạo environment TRƯỚC, tạo flag SAU.** Khi tạo flag, hệ thống tự sinh state row cho mọi environment đang tồn tại. Chiều ngược lại không có: tạo environment mới **không** backfill state cho các flag đã tồn tại.
2. **`apiKey` chỉ hiện đúng một lần** — lúc tạo environment, lúc rotate, và lúc clone. DB chỉ lưu hash SHA-256, không lấy lại được. Copy ngay.
3. **`key` của flag là bất biến.** `PUT /flags/{id}` cố tình bỏ qua trường `key`.

Rate limit (bật sẵn): `/api/v1/auth/**` 10 req/phút theo IP; `/api/v1/sdk/**` 300 req/phút theo environment. Vượt → `429` kèm `Retry-After`.

---

## Thứ tự chạy

| # | Method | Endpoint | Lấy gì ra |
|---|---|---|---|
| 1 | POST | `/api/v1/auth/register` | — (201, không body) |
| 2 | POST | `/api/v1/auth/register` (user 2) | — |
| 3 | POST | `/api/v1/auth/login` (user 2) | `userId` → dùng để invite |
| 4 | POST | `/api/v1/auth/login` (owner) | `accessToken`, `refreshToken` |
| 5 | POST | `/api/v1/auth/refresh` | token mới |
| 6 | POST | `/api/v1/organisations` | `orgId` |
| 7–9 | GET/PUT | `/api/v1/organisations`, `/{orgId}` | — |
| 10–11 | POST/GET | `/api/v1/organisations/{orgId}/members` | — |
| 12 | POST | `/api/v1/projects` | `projectId` |
| 13–15 | GET/PUT | `/api/v1/projects...` | — |
| 16 | POST | `/api/v1/environments` (Development) | `devEnvId`, `devApiKey` |
| 17 | POST | `/api/v1/environments` (Production) | `prodEnvId`, `prodApiKey` |
| 18–20 | GET/PUT | `/api/v1/environments...` | — |
| 21–24 | POST | `/api/v1/flags` × 4 kiểu giá trị | `flagId` từng flag |
| 25–27 | GET/PUT | `/api/v1/flags...` | — |
| 28–33 | GET/PUT | `/api/v1/flags/{flagId}/environments/{envId}` | bật flag theo env |
| 34–40 | GET | `/api/v1/sdk/flags` | kiểm chứng bằng API key |
| 41–42 | POST | `/api/v1/environments/{envId}/clone` | `stagingEnvId`, key mới |
| 43 | GET | `/api/v1/environments/{envId}/export` | snapshot |
| 44–48 | POST | `/api/v1/environments/{envId}/import` | dry-run → SKIP → OVERWRITE |
| 49–52 | DELETE/POST | archive / archived / unarchive | — |
| 53–55 | POST | `/api/v1/environments/{envId}/api-key/rotate` | key mới, key cũ chết |
| 56 | GET | `/api/v1/organisations/{orgId}/audit-log` | lịch sử thao tác |
| 57–59 | GET | `/actuator/health`, `/prometheus` | — |
| 60–64 | DELETE/POST | dọn dẹp + logout | — |

---

## Data mẫu theo từng bước

### 1–4. Auth

```jsonc
// POST /api/v1/auth/register        → 201, không có body
{ "email": "demo.owner@aibles.io", "password": "DemoPassw0rd!", "firstName": "Demo", "lastName": "Owner" }
{ "email": "demo.dev@aibles.io",   "password": "DemoPassw0rd!", "firstName": "Demo", "lastName": "Developer" }

// POST /api/v1/auth/login
{ "email": "demo.owner@aibles.io", "password": "DemoPassw0rd!" }
// → { accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900, userId, email }
```

`password` tối thiểu 8 ký tự. Login user 2 chỉ để lấy `userId` — endpoint mời thành viên nhận `userId`, không nhận email.

### 5. Refresh / 64. Logout

```jsonc
// POST /api/v1/auth/refresh  và  POST /api/v1/auth/logout (204)
{ "refreshToken": "<refreshToken>" }
```

### 6. Tạo organisation

```jsonc
// POST /api/v1/organisations   → người tạo tự động là OWNER
{ "name": "Aibles Demo Corp", "slug": "aibles-demo" }   // slug: ^[a-z0-9-]+$
```

### 10. Mời thành viên

```jsonc
// POST /api/v1/organisations/{orgId}/members
{ "userId": "<userId của user 2>", "role": "ADMIN" }     // OWNER | ADMIN | VIEWER
```

### 12. Tạo project

```jsonc
// POST /api/v1/projects
{ "organisationId": "<orgId>", "name": "Mobile Banking", "description": "App ngân hàng số cho demo feature flag" }
```

### 16–17. Tạo environment

```jsonc
// POST /api/v1/environments
{ "projectId": "<projectId>", "name": "Development", "description": "Môi trường dev" }
{ "projectId": "<projectId>", "name": "Production",  "description": "Môi trường production" }
// → { id, name, description, projectId, apiKey, createdAt }   ⚠ apiKey chỉ có ở response này
```

### 21–24. Tạo flag

```jsonc
// POST /api/v1/flags        key: ^[a-z0-9-_]+$ , valueType: BOOLEAN | STRING | INTEGER | JSON
{ "projectId": "<projectId>", "name": "New Checkout Flow",   "key": "new-checkout-flow",   "valueType": "BOOLEAN" }
{ "projectId": "<projectId>", "name": "Welcome Banner Text", "key": "welcome-banner-text", "valueType": "STRING"  }
{ "projectId": "<projectId>", "name": "Max Cart Items",      "key": "max_cart_items",      "valueType": "INTEGER" }
{ "projectId": "<projectId>", "name": "Promo Config",        "key": "promo-config",        "valueType": "JSON"    }
```

### 29–33. Bật flag theo environment

```jsonc
// PUT /api/v1/flags/{flagId}/environments/{envId}
{ "enabled": true, "rolloutPercent": 100 }                                    // BOOLEAN ở DEV
{ "enabled": true, "value": "Xin chào Aibles!", "rolloutPercent": 100 }       // STRING
{ "enabled": true, "value": "20", "rolloutPercent": 100 }                     // INTEGER (value luôn là string)
{ "enabled": true, "value": "{\"campaign\":\"tet-2026\",\"discount\":15}" }   // JSON escape trong string
{ "enabled": true, "rolloutPercent": 25 }                                     // BOOLEAN ở PROD — mở 25%
```

### 34–38. SDK đọc flag

```bash
curl.exe -H "X-Environment-Key: <devApiKey>" http://localhost:8081/api/v1/sdk/flags
curl.exe -H "X-Environment-Key: <devApiKey>" http://localhost:8081/api/v1/sdk/flags/new-checkout-flow
curl.exe -H "X-Environment-Key: <prodApiKey>" "http://localhost:8081/api/v1/sdk/flags/new-checkout-flow?identifier=user-00001"
```

Response: `[{ "flagKey", "enabled", "value", "valueType", "rolloutPercent" }]`. Flag đã archive không xuất hiện. Với `rolloutPercent < 100`, cùng một `identifier` luôn cho ra cùng kết quả (bucketing tất định) — đổi `identifier` để thấy khác biệt.

Trong PowerShell dùng `curl.exe` (không phải `curl`, vì `curl` là alias của `Invoke-WebRequest`).

### 41. Clone environment

```jsonc
// POST /api/v1/environments/{envId}/clone   → 201
{ "name": "Staging", "description": "Clone từ Development" }
```

Clone nằm cùng project với environment nguồn, copy toàn bộ flag state, và **luôn sinh API key mới** (không bao giờ copy key nguồn).

### 43–47. Export / Import

`GET /api/v1/environments/{envId}/export` trả về:

```jsonc
{
  "schemaVersion": 1,
  "exportedAt": "2026-08-12T10:00:00",
  "environmentId": "...", "environmentName": "Development", "projectId": "...",
  "flags": [ { "key": "new-checkout-flow", "name": "...", "description": null,
               "valueType": "BOOLEAN", "archived": false, "enabled": true,
               "value": null, "rolloutPercent": 100 } ]
}
```

Body export **chính là** `snapshot` của import — dán nguyên xi, các field thừa (`exportedAt`, `environmentId`, …) bị bỏ qua chứ không lỗi 400:

```jsonc
// POST /api/v1/environments/{envId}/import
{ "dryRun": true, "conflictStrategy": "OVERWRITE", "snapshot": <body export> }
```

Hoặc viết tay tối giản:

```jsonc
{
  "dryRun": false,
  "conflictStrategy": "OVERWRITE",     // SKIP (mặc định) | OVERWRITE
  "snapshot": {
    "schemaVersion": 1,
    "flags": [
      { "key": "new-checkout-flow", "valueType": "BOOLEAN", "enabled": true, "rolloutPercent": 50 },
      { "key": "beta-dashboard", "name": "Beta Dashboard", "valueType": "BOOLEAN", "enabled": true, "rolloutPercent": 100 }
    ]
  }
}
```

Kết quả trả về summary + từng item với outcome `CREATED | UPDATED | UNCHANGED | SKIPPED`.

Quy tắc cần nhớ khi demo:

- `dryRun: true` tính toán và báo cáo y hệt lần chạy thật nhưng **không ghi DB** → dùng để preview.
- Project đích lấy từ **environment trên URL**, snapshot không mang `projectId` → không thể import nhầm sang tenant khác.
- Flag chưa tồn tại thì **luôn được tạo**, bất kể `conflictStrategy`; strategy chỉ quyết định khi state đã khác snapshot.
- Import chỉ chuyển **state**; flag đã có sẽ không bị ghi đè `name`/`description`/`archived`/`valueType`.
- `valueType` lệch nhau → luôn `SKIPPED`, kể cả `OVERWRITE`.
- `schemaVersion` khác 1, hoặc `flags` có key trùng → `400`.
- Tối đa 2000 flag mỗi snapshot.

### 49–52. Archive / unarchive

```
DELETE /api/v1/flags/{flagId}            → 204, flag biến mất khỏi SDK
GET    /api/v1/flags/archived?projectId={projectId}
POST   /api/v1/flags/{flagId}/unarchive  → 204
```

### 53. Rotate API key

```
POST /api/v1/environments/{envId}/api-key/rotate   → trả về apiKey mới; key cũ mất hiệu lực ngay (401)
```

### 56. Audit log

```
GET /api/v1/organisations/{orgId}/audit-log?page=0&size=50
```

Mới nhất trước. Actions: `CREATE, UPDATE, DELETE, ARCHIVE, UNARCHIVE, INVITE_MEMBER, REMOVE_MEMBER, ROTATE_API_KEY, CHANGE_STATE, CLONE, IMPORT`.

---

## Quyền theo role

| Thao tác | OWNER | ADMIN | VIEWER |
|---|:--:|:--:|:--:|
| Đọc org/project/environment/flag/audit-log | ✅ | ✅ | ✅ |
| Tạo/sửa project, environment, flag, flag state | ✅ | ✅ | ❌ |
| Mời / gỡ thành viên | ✅ | ✅ | ❌ |
| Rotate API key, clone, export, import | ✅ | ✅ | ❌ |
| Xoá organisation / project / environment | ✅ | ❌ | ❌ |

Không gỡ được OWNER cuối cùng của một organisation.

## Phân trang

Mọi endpoint list dùng chung tham số: `?page=0&size=20&sort=createdAt,asc`. Response bọc trong `PageResponse` (`content`, `page`, `size`, `totalElements`, `totalPages`).
