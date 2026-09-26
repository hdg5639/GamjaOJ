"""Versioned execution settings, also exported to the server at build time.

This is an authenticated worker declaration, not hardware/host attestation.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROFILE = json.loads((ROOT / 'runner/execution-profile.json').read_text())


def contract(root=ROOT):
    files = sorted((root / 'runner').glob('*.py')) + [
        root / 'runner/execution-profile.json', root / 'runner/languages.json', root / 'runner/java-image.txt', root / 'runner/java21-image.txt', root / 'runner/cpp-image.txt', root / 'runner/python-image.txt']
    hashes = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    return {'format': 'runner-execution-contract-v1', 'files': hashes,
            'languages': json.loads((root / 'runner/languages.json').read_text()),
            'profile': json.loads((root / 'runner/execution-profile.json').read_text())}
