"""Package the report and its image as a self-contained Overleaf project."""

from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    root = Path(__file__).resolve().parent
    output = root / "main.zip"
    files = {
        "main.tex": root / "main.tex",
        "figures/pcb-top.png": root / "figures/pcb-top.png",
    }
    for source in files.values():
        if not source.is_file():
            raise FileNotFoundError(source)
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for name, source in files.items():
            archive.write(source, name)
    print(f"Created {output.name} (main.tex and PCB image; compile with pdfLaTeX)")


if __name__ == "__main__":
    main()
