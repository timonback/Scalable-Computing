#!/bin/sh

command -v sbt >/dev/null 2>&1 || { echo "SBT is not installed, aborting." >&2; exit 1; }

for d in ./* ; do
  if [ -d "$d" ]; then
    echo "Opening $d..."
    cd "$d"
    if [ -d "project" ]; then
      sbt docker:publish
    else
      echo "No $d/project dir found, skipping."
    fi
    cd ..
  fi
done
