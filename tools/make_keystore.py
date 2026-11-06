"""產生 Android 簽章金鑰（PKCS12），並印出要貼進 GitHub Secrets 的值。

用法（只需要做一次，金鑰要好好保存，弄丟就無法覆蓋更新已安裝的 App）：
    pip install cryptography
    python tools/make_keystore.py

產出：keystore/release.p12（已被 .gitignore 排除，不會上傳）
"""
import base64
import datetime
import os
import secrets
import sys

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import NameOID
except ImportError:
    sys.exit("請先執行：pip install cryptography")

ALIAS = "liuli"
out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "keystore")
out_path = os.path.normpath(os.path.join(out_dir, "release.p12"))
if os.path.exists(out_path):
    sys.exit(f"已存在 {out_path}，為避免覆蓋舊金鑰而停止。")

password = secrets.token_urlsafe(24)
key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Liuli Launcher")])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(now)
    .not_valid_after(now + datetime.timedelta(days=365 * 30))
    .sign(key, hashes.SHA256())
)
# 用 PBESv2/AES 的加密方式，新版 Java keytool 與 apksigner 都讀得到
encryption = (
    serialization.PrivateFormat.PKCS12.encryption_builder()
    .kdf_rounds(50000)
    .key_cert_algorithm(pkcs12.PBES.PBESv2SHA256AndAES256CBC)
    .hmac_hash(hashes.SHA256())
    .build(password.encode())
)
data = pkcs12.serialize_key_and_certificates(ALIAS.encode(), key, cert, None, encryption)

os.makedirs(out_dir, exist_ok=True)
with open(out_path, "wb") as f:
    f.write(data)

print(f"已建立：{out_path}\n")
print("到 GitHub repo → Settings → Secrets and variables → Actions → New repository secret，")
print("依序新增下列四個（名稱 = 值）：\n")
print(f"KEYSTORE_PASSWORD = {password}")
print(f"KEY_PASSWORD      = {password}")
print(f"KEY_ALIAS         = {ALIAS}")
b64_path = out_path + ".base64.txt"
with open(b64_path, "w", encoding="ascii") as f:
    f.write(base64.b64encode(data).decode())
print(f"KEYSTORE_BASE64   = （內容太長，已存到 {b64_path}，整份複製貼上）")
print("\n密碼請另外存到密碼管理器；keystore 資料夾請備份到安全的地方。")
