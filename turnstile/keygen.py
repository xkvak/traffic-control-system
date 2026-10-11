"""사용: python keygen.py <개인키 경로> <공개키 경로>"""

import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


def main(private_path: Path, public_path: Path) -> None:
    if private_path.exists():
        key = serialization.load_pem_private_key(private_path.read_bytes(), None)
    else:
        key = ec.generate_private_key(ec.SECP256R1())
        private_path.parent.mkdir(parents=True, exist_ok=True)
        private_path.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        print(f"created {private_path}")
    if not public_path.exists():
        public_path.parent.mkdir(parents=True, exist_ok=True)
        public_path.write_bytes(
            key.public_key().public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )
        print(f"created {public_path}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
