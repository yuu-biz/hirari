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
chmod -R a+rX /srv/books
exec smbd --foreground --no-process-group --debug-stdout
