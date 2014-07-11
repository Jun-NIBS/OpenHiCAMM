#!/usr/bin/env bash
set -o errexit
# see https://www.micro-manager.org/wiki/Build_on_MacOS_X#Getting_Micro-Manager_source_code
# and https://www.micro-manager.org/wiki/Download%20Micro-Manager_Latest%20Release

# Configuration variables
FIJIDIR="${FIJIDIR:-/Applications/Fiji.app}"
PORT=4000

# install homebrew and project dependencies
#ruby -e "$(curl -fsSL https://raw.github.com/Homebrew/homebrew/go/install)"
brew install autoconf automake libtool pkg-config swig subversion boost libusb-compat hidapi libdc1394 libgphoto2 freeimage opencv python git

# install latest Java from Oracle
#brew tap caskroom/cask
#brew install brew-cask
brew cask install java

pip install numpy

# install fiji from source
pushd .
git clone git://github.com/fiji/fiji "$FIJIDIR"
cd "$FIJIDIR"
git config remote.origin.url git://fiji.sc/fiji.git
git pull
# build & install deps
./Build.sh
# add the following to fiji/Contents/Info.plist:
defaults write "$PWD"/Contents/Info fiji -dict-add JVMOptions "-Dorg.micromanager.plugin.path=$PWD/mmplugins -Dorg.micromanager.autofocus.path=$PWD/mmautofocus -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$PORT"
defaults write "$PWD"/Contents/Info CFBundleExecutable -string ImageJ-macosx
plutil -convert xml1 Contents/Info.plist
popd

# set JAVA_HOME
export JAVA_HOME="$(/usr/libexec/java_home)"
# set Java source and compile targets to version 1.6 for compatibility with Micro-Manager and Fiji
export JAVACFLAGS='-source 1.6 -target 1.6'

#install micro-manager from source
pushd .
mkdir micromanager
cd micromanager
svn --username guest --password guest checkout https://valelab.ucsf.edu/svn/micromanager2/trunk micromanager
mkdir 3rdpartypublic
svn --username guest --password guest checkout https://valelab.ucsf.edu/svn/3rdpartypublic/classext 3rdpartypublic/classext
cd micromanager
# Configure, build, and install
./autogen.sh
./configure --enable-imagej-plugin="$FIJIDIR" --with-ij-jar="$(echo "$FIJIDIR"/jars/ij-*.jar)"
make -j
make install
popd

# Temporary fix for different versions of rsyntaxtextarea
mv -v "$FIJIDIR"/jars/rsyntaxtextarea-2.5.0.jar "$FIJIDIR"/jars/rsyntaxtextarea-2.5.0.jar.bak
ln -svf "$FIJIDIR"/plugins/Micro-Manager/rsyntaxtextarea.jar "$FIJIDIR"/jars/rsyntaxtextarea-2.5.0.jar
