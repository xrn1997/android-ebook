/**
 * Generate a test EPUB file for local book import testing.
 * Usage: node scripts/generate_test_epub.js
 * Output: test_book.epub (project root)
 */
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const { createWriteStream } = require("fs");

// Minimal ZIP writer (EPUB is a ZIP container)
class ZipWriter {
  constructor(stream) {
    this.stream = stream;
    this.entries = [];
    this.offset = 0;
  }

  writeString(name, content) {
    const data = Buffer.from(content, "utf-8");
    this._writeEntry(name, data);
  }

  writeBuffer(name, data) {
    this._writeEntry(name, data);
  }

  _writeEntry(name, data) {
    const nameBuffer = Buffer.from(name, "utf-8");
    const crc = crc32(data);
    const size = data.length;

    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(0, 8); // STORED
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0, 12);
    localHeader.writeUInt32LE(crc, 14);
    localHeader.writeUInt32LE(size, 18);
    localHeader.writeUInt32LE(size, 22);
    localHeader.writeUInt16LE(nameBuffer.length, 26);
    localHeader.writeUInt16LE(0, 28);

    this.stream.write(localHeader);
    this.stream.write(nameBuffer);
    this.stream.write(data);

    this.entries.push({
      name: nameBuffer,
      crc,
      size,
      localOffset: this.offset,
    });

    this.offset += localHeader.length + nameBuffer.length + data.length;
  }

  writeCentralDirectory() {
    const cdStart = this.offset;
    for (const entry of this.entries) {
      const cdEntry = Buffer.alloc(46);
      cdEntry.writeUInt32LE(0x02014b50, 0);
      cdEntry.writeUInt16LE(20, 4);
      cdEntry.writeUInt16LE(20, 6);
      cdEntry.writeUInt16LE(0, 8);
      cdEntry.writeUInt16LE(0, 10);
      cdEntry.writeUInt16LE(0, 12);
      cdEntry.writeUInt16LE(0, 14);
      cdEntry.writeUInt32LE(entry.crc, 16);
      cdEntry.writeUInt32LE(entry.size, 20);
      cdEntry.writeUInt32LE(entry.size, 24);
      cdEntry.writeUInt16LE(entry.name.length, 28);
      cdEntry.writeUInt16LE(0, 30);
      cdEntry.writeUInt16LE(0, 32);
      cdEntry.writeUInt16LE(0, 34);
      cdEntry.writeUInt16LE(0, 36);
      cdEntry.writeUInt32LE(0, 38);
      cdEntry.writeUInt32LE(entry.localOffset, 42);

      this.stream.write(cdEntry);
      this.stream.write(entry.name);
      this.offset += cdEntry.length + entry.name.length;
    }
    const cdSize = this.offset - cdStart;

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(this.entries.length, 8);
    eocd.writeUInt16LE(this.entries.length, 10);
    eocd.writeUInt32LE(cdSize, 12);
    eocd.writeUInt32LE(cdStart, 16);
    eocd.writeUInt16LE(0, 20);

    this.stream.write(eocd);
    this.offset += eocd.length;
  }
}

// CRC32
const crc32Table = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c;
  }
  return table;
})();

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = crc32Table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
  return ((crc ^ 0xffffffff) >>> 0);
}

// ===== EPUB content =====

const CHAPTERS = [
  {
    title: "\u7b2c\u4e00\u7ae0 \u521d\u5165\u6c5f\u6e56",
    paragraphs: [
      "\u5c11\u5e74\u7ad9\u5728\u5c71\u5dc5\uff0c\u671b\u7740\u8fdc\u65b9\u7684\u57ce\u9547\uff0c\u5fc3\u4e2d\u5145\u6ee1\u4e86\u671f\u5f85\u3002",
      "\u4ed6\u5df2\u7ecf\u5728\u8fd9\u5ea7\u5c71\u4e0a\u4fee\u70bc\u4e86\u4e09\u5e74\uff0c\u5982\u4eca\u7ec8\u4e8e\u8981\u4e0b\u5c71\u5386\u7ec3\u3002",
      "\u5e08\u7236\u544a\u8bc9\u4ed6\uff0c\u5916\u9762\u7684\u4e16\u754c\u5f88\u7cbe\u5f69\uff0c\u4e5f\u5f88\u5371\u9669\u3002",
      "\u4f46\u4ed6\u5e76\u4e0d\u5bb3\u6015\uff0c\u56e0\u4e3a\u4ed6\u6709\u4e00\u9897\u575a\u5b9a\u7684\u5fc3\u3002",
      "\u80cc\u4e0a\u884c\u56ca\uff0c\u5e26\u4e0a\u5e72\u7cae\uff0c\u5c11\u5e74\u8e0f\u4e0a\u4e86\u672a\u77e5\u7684\u65c5\u9014\u3002",
    ],
  },
  {
    title: "\u7b2c\u4e8c\u7ae0 \u57ce\u9547\u5947\u9047",
    paragraphs: [
      "\u57ce\u9547\u7684\u8857\u9053\u4e0a\u4eba\u6765\u4eba\u5f80\uff0c\u70ed\u95f9\u975e\u51e1\u3002",
      "\u5c11\u5e74\u7b2c\u4e00\u6b21\u89c1\u5230\u8fd9\u4e48\u591a\u7684\u4eba\uff0c\u4e0d\u7981\u6709\u4e9b\u773c\u82b1\u7f2d\u4e71\u3002",
      "\u8857\u8fb9\u7684\u5c0f\u8d29\u5728\u53eb\u5356\u7740\u5404\u79cd\u5404\u6837\u7684\u8d27\u7269\uff0c\u6709\u4e39\u836f\uff0c\u6709\u5175\u5668\uff0c\u4e5f\u6709\u529f\u6cd5\u79d8\u7c4d\u3002",
      "\u4ed6\u8d70\u5230\u4e00\u5bb6\u5ba2\u6808\u524d\uff0c\u51b3\u5b9a\u5148\u4f11\u606f\u4e00\u4e0b\u3002",
      "\u5ba2\u6808\u7684\u8001\u677f\u662f\u4e2a\u80d6\u5b50\uff0c\u770b\u8d77\u6765\u5f88\u548c\u5584\u3002",
      "\u80d6\u5b50\u7b11\u7740\u95ee\u9053\uff1a\u5ba2\u5b98\uff0c\u6765\u4e00\u95f4\u4e0a\u623f\uff1f",
      "\u5c11\u5e74\u70b9\u70b9\u5934\uff0c\u4ece\u6000\u91cc\u638f\u51fa\u51e0\u679a\u94dc\u94b1\u3002",
    ],
  },
  {
    title: "\u7b2c\u4e09\u7ae0 \u795e\u79d8\u8001\u8005",
    paragraphs: [
      "\u6b63\u5f53\u5c11\u5e74\u51c6\u5907\u4f11\u606f\u7684\u65f6\u5019\uff0c\u4e00\u4f4d\u767d\u8863\u8001\u8005\u8d70\u8fdb\u4e86\u5ba2\u6808\u3002",
      "\u8001\u8005\u7684\u773c\u795e\u6df1\u9082\uff0c\u4eff\u4f5b\u80fd\u770b\u7a7f\u4e00\u5207\u3002",
      "\u4ed6\u5728\u5c11\u5e74\u5bf9\u9762\u5750\u4e0b\uff0c\u5fae\u5fae\u4e00\u7b11\uff1a\u5e74\u8f7b\u4eba\uff0c\u4f60\u4ece\u54ea\u91cc\u6765\uff1f",
      "\u5c11\u5e74\u5982\u5b9e\u56de\u7b54\u4e86\u81ea\u5df1\u7684\u6765\u5386\u3002",
      "\u8001\u8005\u542c\u540e\uff0c\u773c\u4e2d\u95ea\u8fc7\u4e00\u4e1d\u5f02\u8272\uff1a\u539f\u6765\u4f60\u662f\u4ed6\u7684\u5f1f\u5b50\u3002",
      "\u8001\u8005\u7ee7\u7eed\u95ee\u9053\uff1a\u4f60\u5e08\u7236\u53ef\u66fe\u6559\u8fc7\u4f60\u4e00\u5957\u5251\u6cd5\uff1f",
      "\u5c11\u5e74\u5fc3\u4e2d\u4e00\u60ca\uff0c\u8fd9\u5957\u5251\u6cd5\u5e08\u7236\u53ea\u6559\u8fc7\u4ed6\u51e0\u62db\uff0c\u5916\u4eba\u4e0d\u5e94\u77e5\u9053\u3002",
      "\u8001\u8005\u7b11\u9053\uff1a\u653e\u5fc3\uff0c\u6211\u6ca1\u6709\u6076\u610f\u3002",
    ],
  },
  {
    title: "\u7b2c\u56db\u7ae0 \u4fee\u70bc\u7a81\u7834",
    paragraphs: [
      "\u5728\u8001\u8005\u7684\u6307\u70b9\u4e0b\uff0c\u5c11\u5e74\u5f00\u59cb\u91cd\u65b0\u4fee\u70bc\u90a3\u5957\u5251\u6cd5\u3002",
      "\u539f\u6765\u4ed6\u4e4b\u524d\u7684\u4fee\u70bc\u65b9\u5f0f\u6709\u8bf8\u591a\u4e0d\u59a5\uff0c\u8001\u8005\u4e00\u4e00\u6307\u51fa\u3002",
      "\u7ecf\u8fc7\u4e09\u5929\u4e09\u591c\u7684\u82e6\u7ec3\uff0c\u5c11\u5e74\u7ec8\u4e8e\u7a81\u7834\u4e86\u74f6\u9888\u3002",
      "\u5251\u610f\u5982\u6c34\uff0c\u884c\u4e91\u6d41\u6c34\u822c\u81ea\u7136\u3002",
      "\u8001\u8005\u6ee1\u610f\u5730\u70b9\u70b9\u5934\uff1a\u4e0d\u9519\uff0c\u4f60\u7684\u5929\u8d4b\u5f88\u597d\u3002",
      "\u8001\u8005\u8bf4\uff1a\u4fee\u70bc\u4e4b\u9053\uff0c\u5728\u4e8e\u5fc3\u5883\u3002\u5fc3\u9759\u5219\u5251\u660e\uff0c\u5fc3\u4e71\u5219\u5251\u6697\u3002",
      "\u5c11\u5e74\u5c06\u8fd9\u53e5\u8bdd\u7262\u7262\u8bb0\u5728\u5fc3\u4e2d\u3002",
    ],
  },
  {
    title: "\u7b2c\u4e94\u7ae0 \u8e0f\u4e0a\u5f81\u9014",
    paragraphs: [
      "\u544a\u522b\u4e86\u8001\u8005\uff0c\u5c11\u5e74\u7ee7\u7eed\u4ed6\u7684\u65c5\u7a0b\u3002",
      "\u8fd9\u4e00\u6b21\uff0c\u4ed6\u7684\u5fc3\u4e2d\u591a\u4e86\u4e00\u4efd\u4ece\u5bb9\u548c\u81ea\u4fe1\u3002",
      "\u524d\u65b9\u7684\u8def\u8fd8\u5f88\u957f\uff0c\u4f46\u4ed6\u5df2\u7ecf\u4e0d\u518d\u8ff7\u832b\u3002",
      "\u8fdc\u5904\u7684\u5929\u9645\u7ebf\u4e0a\uff0c\u4e00\u5ea7\u5dc4\u5cd4\u7684\u5c71\u8109\u82e5\u9690\u82e5\u73b0\u3002",
      "\u90a3\u5c31\u662f\u4ed6\u6b64\u884c\u7684\u76ee\u7684\u5730\u2014\u2014\u5929\u5251\u5b97\u3002",
      "\u4f20\u8bf4\u4e2d\uff0c\u5929\u5251\u5b97\u85cf\u7740\u5929\u4e0b\u7b2c\u4e00\u7684\u5251\u6cd5\u79d8\u7c4d\u3002",
      "\u5c11\u5e74\u52a0\u5feb\u4e86\u811a\u6b65\uff0c\u5411\u7740\u76ee\u6807\u524d\u8fdb\u3002",
      "\uff08\u5168\u4e66\u5b8c\uff09",
    ],
  },
];

const BOOK_TITLE = "\u51e1\u4eba\u4fee\u4ed9\u4f20";
const BOOK_AUTHOR = "\u5fd8\u8bed";

const CONTAINER_XML = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">',
  '<rootfiles>',
  '<rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>',
  '</rootfiles>',
  '</container>',
].join("\n");

function makeXhtml(ch) {
  const pTags = ch.paragraphs.map((p) => "<p>" + p + "</p>").join("\n");
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<html xmlns="http://www.w3.org/1999/xhtml">',
    "<head><title>" + ch.title + "</title></head>",
    "<body>",
    "<h1>" + ch.title + "</h1>",
    pTags,
    "</body>",
    "</html>",
  ].join("\n");
}

function makeOpf() {
  const manifestItems = CHAPTERS.map(
    (_, i) =>
      '    <item id="ch' + (i + 1) + '" href="chapter' + (i + 1) + '.xhtml" media-type="application/xhtml+xml"/>'
  ).join("\n");
  const spineItems = CHAPTERS.map(
    (_, i) => '    <itemref idref="ch' + (i + 1) + '"/>'
  ).join("\n");

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid">',
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">',
    "<dc:title>" + BOOK_TITLE + "</dc:title>",
    "<dc:creator>" + BOOK_AUTHOR + "</dc:creator>",
    '<dc:identifier id="bookid">urn:uuid:test-epub-001</dc:identifier>',
    "<dc:language>zh-CN</dc:language>",
    "</metadata>",
    "<manifest>",
    manifestItems,
    '    <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>',
    '    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
    "</manifest>",
    "<spine>",
    spineItems,
    "</spine>",
    "</package>",
  ].join("\n");
}

function makeNcx() {
  const navPoints = CHAPTERS.map(
    (ch, i) =>
      '    <navPoint id="ch' + (i + 1) + '" playOrder="' + (i + 1) + '"><navLabel><text>' + ch.title + "</text></navLabel><content src=\"chapter" + (i + 1) + '.xhtml"/></navPoint>'
  ).join("\n");

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">',
    "<docTitle><text>" + BOOK_TITLE + "</text></docTitle>",
    "<navMap>",
    navPoints,
    "</navMap>",
    "</ncx>",
  ].join("\n");
}

function makeCoverPng() {
  function chunk(type, data) {
    const typeAndData = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE((crc32(typeAndData) >>> 0), 0);
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    return Buffer.concat([len, typeAndData, crc]);
  }

  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(1, 0);
  ihdrData.writeUInt32BE(1, 4);
  ihdrData[8] = 8;
  ihdrData[9] = 2;
  ihdrData[10] = 0;
  ihdrData[11] = 0;
  ihdrData[12] = 0;
  const ihdr = chunk("IHDR", ihdrData);

  const rawRow = Buffer.from([0, 255, 0, 0]);
  const idat = chunk("IDAT", zlib.deflateSync(rawRow));
  const iend = chunk("IEND", Buffer.alloc(0));

  return Buffer.concat([sig, ihdr, idat, iend]);
}

function main() {
  const outputPath = path.join(__dirname, "..", "test_book.epub");
  const stream = createWriteStream(outputPath);
  const zip = new ZipWriter(stream);

  zip.writeString("mimetype", "application/epub+zip");
  zip.writeString("META-INF/container.xml", CONTAINER_XML);
  zip.writeString("content.opf", makeOpf());
  zip.writeString("toc.ncx", makeNcx());
  zip.writeBuffer("images/cover.png", makeCoverPng());

  for (let i = 0; i < CHAPTERS.length; i++) {
    zip.writeString("chapter" + (i + 1) + ".xhtml", makeXhtml(CHAPTERS[i]));
  }

  zip.writeCentralDirectory();
  stream.end();

  stream.on("finish", () => {
    const stats = fs.statSync(outputPath);
    console.log("Generated: " + outputPath);
    console.log("  Size: " + (stats.size / 1024).toFixed(1) + " KB");
    console.log("  Title: " + BOOK_TITLE);
    console.log("  Author: " + BOOK_AUTHOR);
    console.log("  Chapters: " + CHAPTERS.length);
    console.log("  Cover: images/cover.png (1x1 red placeholder)");
  });
}

main();
