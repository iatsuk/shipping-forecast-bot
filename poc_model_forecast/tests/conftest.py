import json
import pathlib
import types
import pytest

@pytest.fixture(scope="module")
def poc():
    """Load code cells from the proof-of-concept notebook as a module."""
    nb_path = pathlib.Path(__file__).resolve().parents[1] / "proof_of_concept.ipynb"
    with nb_path.open(encoding="utf-8") as f:
        nb = json.load(f)
    module = types.ModuleType("poc_module")
    for cell in nb["cells"]:
        if cell.get("cell_type") == "code":
            source = "".join(cell.get("source", []))
            exec(source, module.__dict__)
    return module
