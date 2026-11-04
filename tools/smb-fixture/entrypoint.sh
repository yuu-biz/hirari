#!/bin/sh
set -e
# The tests search captured logs for this password (docs/spec.md section 6.6).
id bookworm7 >/dev/null 2>&1 || adduser -D -H bookworm7
printf 'hirari-fixture-Pw7q\nhirari-fixture-Pw7q\n' | smbpasswd -s -a bookworm7 >/dev/null

# Bytes the tests regenerate to check reads: the AES-128-CTR keystream with key 00..0f and IV 0.
pattern() {
  head -c "$1" /dev/zero | openssl enc -aes-128-ctr -nosalt -K 000102030405060708090a0b0c0d0e0f -iv 00000000000000000000000000000000
}
mkdir -p /srv/books/many /srv/books/漫画
pattern 8388731 > /srv/books/big.bin
pattern 1000 > /srv/books/small.bin
: > /srv/books/empty.bin
pattern 1000 > /srv/books/漫画/第1巻.bin
i=0
while [ $i -lt 300 ]; do
  printf 'page %03d' $i > /srv/books/many/$(printf 'p%03d.txt' $i)
  i=$((i + 1))
done
# A folder book: images that start with the PNG signature and go on with the pattern, one without an extension.
png() {
  { printf '\211PNG\r\n\032\n'; pattern "$1"; } > "$2"
}
mkdir -p /srv/books/画像/おまけ
png 3000 /srv/books/画像/第2話.png
png 4000 /srv/books/画像/第10話.png
png 2000 /srv/books/画像/第1話.png
png 1000 /srv/books/画像/scan
png 1000 /srv/books/画像/おまけ/01.png
printf 'memo' > /srv/books/画像/メモ.txt
# A series whose next and previous books the reader finds. Only the names count.
mkdir -p /srv/books/シリーズ/おまけ /srv/books/シリーズ/空
: > /srv/books/シリーズ/第1巻.cbz
: > /srv/books/シリーズ/第2巻.zip
: > /srv/books/シリーズ/第10巻.pdf
png 1000 /srv/books/シリーズ/おまけ/01.png
printf 'memo' > /srv/books/シリーズ/空/説明.txt
# A book of 200 stored pages of 150,000 bytes for the SMB cover target (docs/spec.md section 5.2). The pages go in
# out of name order, so the cover, p000.jpg, is the 101st entry and sits in the middle of the archive.
mkdir -p /tmp/cover
i=0
while [ $i -lt 200 ]; do
  pattern 150000 > /tmp/cover/$(printf 'p%03d.jpg' $i)
  i=$((i + 1))
done
rm -f /srv/books/cover.zip
(cd /tmp/cover && zip -q -0 -X /srv/books/cover.zip p1*.jpg p0*.jpg)
rm -rf /tmp/cover
# A book of 24 small PNG pages that decode, for the device test of the whole route (app/src/androidTest, RouteSmbTest). The
# pages of cover.zip are not images.
mkdir -p /tmp/route
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAADwAAABUCAIAAABROcMSAAACzElEQVR42tXV5wIQAgCF0RsRFZVSZLTQQCErq1IyQtNoC2mIKHvUV6SlQZpmg6bRpDS1tLRDlKxI9p7lvsZ9hfPnSAco+A+H/UHRXyj+A6X2ccxXHP855T/hpI+o8j6nbeGMDZy9lvNXcdEy6iym/ttc8SZXz6Lx6zSfxo2TaD2Bm17k1mfpNIquw7lrKPcM4oF+PPIY9OLxh+l/P0/2YFg3nrmd0R157hZeasfEVky+genNeKMRsxvy1uUsqMeS2iy/kHfPY11NNtZg66l8UJmPK7G7HF8cx9dl+LYkOug/Dv2Lwr9x5E8c9R2l91L2S078lIo7OeVDqm2j+ibOWs+5q7lgBZcs5dKFNJjHVXO4dgZNX+X6KbR8mbbjuPl5bhtDlxHc+RTdB3PfAB7qS8/e9HmUJx5k4L0MuZun72BkZ8Z24IX2jG/DKy2Yeh2vNWHmNcy9kvmXsagu71zMylqsOYf3zmTz6Wyvyo6T2VWBz05gz7F8czTfl0BxzD8WQ3HMPx+B4ph/LYLimH8/HMUx/1kIxTH/fQiKY/73YBTHvL8AymM+gOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXbjimF244phduOKYXfj/jQl2BGZXQX4AAAAASUVORK5CYII=' | base64 -d > /tmp/route/page.png
i=0
while [ $i -lt 24 ]; do
  cp /tmp/route/page.png /tmp/route/$(printf 'p%02d.png' $i)
  i=$((i + 1))
done
rm -f /srv/books/route.zip
(cd /tmp/route && zip -q -0 -X /srv/books/route.zip p*.png)
rm -rf /tmp/route
chmod -R a+rX /srv/books
exec smbd --foreground --no-process-group --debug-stdout
