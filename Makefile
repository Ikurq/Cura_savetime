.PHONY: help test lint format check

help:
	@echo "AI-Driven Development Task Runner"
	@echo "Usage: make [test|lint|format|check]"

test:
	@powershell -ExecutionPolicy Bypass -File ./run.ps1 test || ./run.sh test

lint:
	@powershell -ExecutionPolicy Bypass -File ./run.ps1 lint || ./run.sh lint

format:
	@powershell -ExecutionPolicy Bypass -File ./run.ps1 format || ./run.sh format

check:
	@powershell -ExecutionPolicy Bypass -File ./run.ps1 check || ./run.sh check
