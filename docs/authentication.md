# Autenticación

La galería y la sincronización dependen solo de la abstracción `AwsCredentialsProvider` (`getCredentials()`, `canRenewAutomatically`). Un adaptador entrega esas credenciales al SDK **en cada solicitud firmada**, así que la caducidad se aplica de inmediato y las transferencias fallan de forma controlada.

## Resumen

| | Método A — temporales | Método B — estáticas |
|---|---|---|
| Qué se guarda | Access key (`ASIA…`) + secret + **session token** + **expiración** | Access key (`AKIA…`) + secret |
| Caducidad | Sí (la que fije tu identidad de origen) | Nunca, hasta que la desactives |
| Renovación en la app | **No automática.** La app avisa y pide credenciales nuevas | No aplica |
| Riesgo si se filtran | Acotado al tiempo de sesión | Hasta revocación manual |
| Recomendado | Sí | Solo usuarios avanzados |
| Obtención | CLI (`assume-role`, `get-session-token`, `export-credentials`) | `iam create-access-key` |

## Por qué no hay renovación automática (investigación)

Para renovar sin backend propio hace falta que la app pueda **demostrar una identidad a AWS**. STS no entrega credenciales a quien llama anónimamente: todas sus operaciones, salvo `AssumeRoleWithWebIdentity` y `AssumeRoleWithSAML`, exigen una solicitud firmada con credenciales existentes, y esas dos exigen un token de un proveedor de identidad (IdP) que ya esté configurado como confianza del rol. No se puede «inventar» una renovación sin una de estas piezas. Opciones evaluadas:

### 1. Credenciales temporales aportadas por el usuario — IMPLEMENTADA

El usuario obtiene credenciales de una identidad que ya tiene (usuario IAM + MFA, rol, perfil SSO) y las pega. La app valida formato, exige session token y expiración futura, muestra la caducidad con cuenta atrás, avisa 15 min antes y, al caducar, detiene el trabajo. Formatos reconocidos: JSON de `sts assume-role` / `get-session-token` (objeto `Credentials`), JSON plano de `aws configure export-credentials` (formato `credential_process`) y líneas `AWS_ACCESS_KEY_ID=…` (bash, PowerShell, cmd).

Límites: renovación **manual**; la duración máxima depende de la identidad de origen (p. ej. `assume-role` hasta el máximo del rol; encadenar roles limita la sesión a 1 h; `get-session-token` de usuario IAM hasta 36 h).

### 2. `AssumeRole` desde una clave de arranque — evaluada, no implementada

La app guardaría una clave estática cuyo único permiso fuera `sts:AssumeRole` sobre un rol dedicado, y obtendría sesiones cortas por sí misma (`aws.sdk.kotlin:sts`, `StsAssumeRoleCredentialsProvider` + caché). Sí permitiría renovación automática real. **No se implementó** porque: (a) sigue habiendo un secreto permanente en el teléfono (solo con menor alcance), lo que difumina la diferencia entre los dos métodos; (b) añade un tercer modelo de credenciales que el alcance no pide. Encajaría sin tocar la galería: una nueva implementación de `AwsCredentialsProvider` con `canRenewAutomatically = true`. Requisitos: rol con política de confianza hacia el usuario IAM (ver [iam-policy.md](iam-policy.md)) y `sts:AssumeRole` en el usuario.

### 3. Federación web (`AssumeRoleWithWebIdentity`) — evaluada, no implementada

Sin Cognito ni backend es técnicamente posible: login OIDC en la app (p. ej. Google), intercambio del token por credenciales STS, renovación con el *refresh token* del IdP. Requisitos que la hacen inadecuada como valor por defecto: el usuario debe registrar un **proveedor OIDC en IAM**, crear un cliente OAuth propio (la app no puede publicar un `client_id` compartido sin convertirse en servicio), configurar la política de confianza del rol con condiciones sobre `aud`/`sub`, y la renovación queda sujeta a los límites del IdP. La complejidad y la superficie de ataque (flujo OAuth en móvil) no compensan como comportamiento por defecto.

### 4. IAM Identity Center (SSO) con *device authorization* — evaluada, no implementada

Es la alternativa oficial más prometedora: la app se registra como cliente OIDC (`RegisterClient`), inicia el flujo de dispositivo (`StartDeviceAuthorization`; el usuario aprueba en el navegador), obtiene un token (`CreateToken` con *device code*), pide credenciales de rol (`GetRoleCredentials`) y las renueva con el *refresh token* de `CreateToken` hasta donde permita la sesión que haya configurado el administrador. No requiere Cognito, Lambda ni backend; el SDK de Kotlin publica los módulos `sso` y `ssooidc`. **No se implementó** porque exige que el usuario tenga IAM Identity Center habilitado (con usuarios, permission sets y asignación de cuenta), no pudo verificarse aquí sin una instancia real y un flujo no comprobado con credenciales reales no debe presentarse como funcional. Es el siguiente paso recomendado: otra implementación de `AwsCredentialsProvider` y una pantalla de configuración (start URL, región SSO, cuenta, rol).

### 5. Cognito — excluido por el alcance

No se usa (ni identity pools ni user pools).

## Comportamiento cuando caducan las credenciales

1. Cada solicitud resuelve credenciales a través de `CredentialsRepository`; si `now ≥ expiración − 60 s` lanza `CREDENTIALS_EXPIRED` **antes** de enviar nada.
2. `BackupEngine` comprueba las credenciales antes de tocar ningún elemento. Si fallan, devuelve `NeedsCredentials`; la cola queda intacta (`QUEUED`).
3. Si caducan a mitad de una subida, el elemento vuelve a `QUEUED` sin error ni intento consumido. **Nada se marca `BACKED_UP`** sin PUT + verificación (`HEAD`).
4. La interfaz muestra un aviso en Respaldo y en Seguridad; se registra un evento.
5. Al guardar credenciales nuevas, `BackupController.onCredentialsRenewed()` limpia el aviso y relanza WorkManager: el trabajo se reanuda solo.
6. Si S3 devuelve `ExpiredToken` (reloj desviado, sesión revocada), se trata igual.

## Protección local (ambos métodos)

- JSON `{método, access key, secret, token, expiración}` → AES-256-GCM con clave no exportable del Android Keystore → archivo en `noBackupFilesDir/secrets/` (excluido de copias de seguridad).
- Nunca en Room, DataStore, logs ni cuentas de Google; `AwsCredentials.toString()` oculta valores; toda salida de texto pasa por `Redactor` (access keys, tokens, firmas, cadenas base64 largas).
- Las pantallas de credenciales usan `FLAG_SECURE` (sin capturas ni miniatura en recientes), campos enmascarados y sin autocorrección.
- El estado de autenticación vive en `CredentialsRepository`, fuera de los modelos de interfaz; tras guardar, los campos del formulario se vacían.
- **No afirmamos** que el Keystore haga imposible extraer una credencial en un dispositivo comprometido: malware con privilegios de esta app o root puede pedir al Keystore que descifre. Por eso se recomiendan credenciales temporales y mínimo privilegio. Ver [security.md](security.md).
