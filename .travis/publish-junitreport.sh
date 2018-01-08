#!/bin/bash
if [ "$TRAVIS_REPO_SLUG" == "ZuInnoTe/hadoopoffice" ] && [ "$TRAVIS_PULL_REQUEST" == "false" ] && [ "$TRAVIS_BRANCH" == "master" ]; then

echo -e "Publishing test results...\n"

# copy to home
mkdir -p $HOME/fileformat/tests-latest
cp -R fileformat/build/test-results/junit-platform $HOME/fileformat/tests-latest

# Get to the Travis build directory, configure git and clone the repo
cd $HOME
git config --global user.email "travis@travis-ci.org"
git config --global user.name "travis-ci"
git clone --quiet --branch=gh-pages https://${GH_TOKEN}@github.com/ZuInnoTe/hadoopoffice gh-pages > /dev/null

# Commit and Push the Changes
cd gh-pages
git rm -rf ./tests/fileformat
mkdir -p ./tests/fileformat
cp -Rf $HOME/fileformat/tests-latest ./tests/fileformat
git add -f .
git commit -m "Lastest javadoc on successful travis build $TRAVIS_BUILD_NUMBER auto-pushed to gh-pages"
git push -fq origin gh-pages > /dev/null

fi
