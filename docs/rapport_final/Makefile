LATEX = xelatex
BIBTEX = bibtex
TARGET = main
OUTPUT = ../../Rapport_Final_FCS.pdf

.PHONY: all pdf clean distclean

all: pdf

pdf:
	$(LATEX) $(TARGET).tex
	$(BIBTEX) $(TARGET)
	$(LATEX) $(TARGET).tex
	$(LATEX) $(TARGET).tex
	cp -f $(TARGET).pdf $(OUTPUT)

clean:
	rm -f $(TARGET).aux $(TARGET).bbl $(TARGET).blg $(TARGET).lof $(TARGET).log \
	      $(TARGET).lot $(TARGET).out $(TARGET).toc $(TARGET).fls $(TARGET).fdb_latexmk

distclean: clean
	rm -f $(TARGET).pdf $(OUTPUT)
