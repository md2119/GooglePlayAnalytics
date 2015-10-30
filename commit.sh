#!/bin/bash
git config --global user.name md2119
git init 
git rm -r --cached *
git remote rm origin
git remote add origin https://github.com/md2119/GooglePlayAnalytics.git
#git pull origin master
git add *
git commit -m "Google Play Crawler"
git push -u origin master

