#!/bin/bash
set -o errexit

# Copyright 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

INPUT_DATA="${INPUT_DIR}/eccdata.txt"
OUTPUT_DATA="${OUTPUT_DIR}/eccdata"
PROTOBUF_DIR="${LOCAL_TOOLSET_DIR}/proto"
PROTOBUF_FILE="${PROTOBUF_DIR}/protobuf_ecc_data.proto"
RAW_DATA="${INTERMEDIATE_DIR}/eccdata.raw"

read -d "" PYTHON_COMMAND << END || :
${ANDROID_BUILD_TOP}/prebuilts/python/${KERNEL}-x86/2.7.5/bin/python
END
PYTHONPATH="${PYTHONPATH}:${INTERMEDIATE_DIR}"
PYTHONPATH="${PYTHONPATH}:${ANDROID_BUILD_TOP}/external/nanopb-c/generator/"

if ! [ -x "${PYTHON_COMMAND}" ] ; then
  echo "Missing ${PYTHON_COMMAND}." 1>&2
  exit 1
fi

"${PROTOC_COMMAND}" \
  --python_out="${INTERMEDIATE_DIR}" \
  --proto_path="${PROTOBUF_DIR}" \
  "${PROTOBUF_FILE}"
