.PHONY: all pdf overleaf clean

all: pdf overleaf

pdf:
	latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex

overleaf:
	python3 make_overleaf.py

clean:
	latexmk -c main.tex
