SHELL = /bin/bash
SRC = src
PYTHONSRC = $(SRC)/python
RENDER = $(PYTHONSRC)/render.py
export SCRIPT = script

MAKE_CONFIG_FILES = $(shell find . -depth 1 -name '*config.mk')

include $(MAKE_CONFIG_FILES)

%: %.jinja $(MAKE_CONFIG_FILES)
	$(RENDER) $<
