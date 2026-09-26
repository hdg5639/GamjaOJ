"""Export the exact Runner source/settings snapshot for the backend build."""
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from runner.execution_contract import ROOT, contract

path = ROOT / 'backend/src/main/resources/runner-execution-contract.json'
path.write_text(json.dumps(contract(), sort_keys=True, separators=(',', ':')) + '\n')
