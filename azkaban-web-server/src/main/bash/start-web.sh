#!/bin/bash

script_dir=$(dirname $0)

${script_dir}/internal/internal-start-web.sh > /dev/null 2>&1 &
