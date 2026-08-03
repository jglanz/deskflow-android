#! /bin/bash

#
# MIT License
#
# Copyright (c) 2025 Jonathan Glanz
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

VERSION_TYPE=$1
if ! [[ "$VERSION_TYPE" =~ ^(major|minor|patch)$ ]]; then
  echo "Usage: $0 {major|minor|patch}"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_FILE="${ROOT_DIR}/app/build.gradle.kts"
VERSION_PROPS="${ROOT_DIR}/version.properties"
MAJOR=$(grep '^major=' $VERSION_PROPS | cut -d'=' -f2)
MINOR=$(grep '^minor=' $VERSION_PROPS | cut -d'=' -f2)
PATCH=$(grep '^patch=' $VERSION_PROPS | cut -d'=' -f2)

if [ "$VERSION_TYPE" = "major" ]; then
  MAJOR=$((MAJOR + 1))
  MINOR=0
  PATCH=0
elif [ "$VERSION_TYPE" = "minor" ]; then
  MINOR=$((MINOR + 1))
  PATCH=0
else
  PATCH=$((PATCH + 1))
fi
VERSION="$MAJOR.$MINOR.$PATCH"
# Fail fast: minor/patch >= 100 would collide the %d%02d%02d versionCode scheme
# (e.g. 1.0.100 and 10.1.0 both encode to 100100). Keep in sync with
# buildSrc ProcessHelpers.kt:readVersionProperties.
if [ "$MINOR" -ge 100 ] || [ "$PATCH" -ge 100 ]; then
  echo "ERROR: minor ($MINOR) or patch ($PATCH) >= 100 would collide the versionCode scheme" >&2
  exit 1
fi
VERSION_CODE=$(echo $VERSION | awk -F. '{ printf("%d%02d%02d", $1,$2,$3) }')
echo "major=$MAJOR" > $VERSION_PROPS
echo "minor=$MINOR" >> $VERSION_PROPS
echo "patch=$PATCH" >> $VERSION_PROPS
echo "versionName=$VERSION" >> $VERSION_PROPS
echo "versionCode=$VERSION_CODE" >> $VERSION_PROPS

#sed -i.bak -E "s/versionName.*=.*$/versionName = \"$VERSION\"/" $BUILD_FILE
#rm $BUILD_FILE.bak
#sed -i.bak -E "s/versionCode.*=.*$/versionCode = $VERSION_CODE/" $BUILD_FILE
#rm $BUILD_FILE.bak
