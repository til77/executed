# Third-Party Notices and Licenses

`Executed.jar` bundles the following open-source libraries. Their respective copyright notices, attributions, and license terms are listed below.

Executed itself is licensed under the MIT License (see `LICENSE`, bundled in the jar as `META-INF/LICENSE`).

The full texts of the third-party licenses referenced below are located in the `third-party-licenses/` directory of the source repository and are bundled in `Executed.jar` under `META-INF/third-party-licenses/`:

- `GPL-2.0-with-Classpath-Exception.txt` – GNU General Public License v2 with the Classpath Exception
- `Apache-2.0.txt` – Apache License, Version 2.0
- `MPL-2.0.txt` – Mozilla Public License, Version 2.0
- `openjfx-jpeg_fx.md`, `openjfx-mesa3d.md`, `openjfx-pipewire.md` – third-party notices of components included in OpenJFX

---

## 1. OpenJFX (JavaFX) 23.0.2
- **Artifacts:** `org.openjfx:javafx-base`, `org.openjfx:javafx-graphics`, `org.openjfx:javafx-controls`, `org.openjfx:javafx-swing` (Linux x64, Windows x64, and macOS aarch64 classifiers)
- **Copyright:** Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
- **License:** GNU General Public License, version 2, with the Classpath Exception (GPLv2+CE) – full text in `GPL-2.0-with-Classpath-Exception.txt`
- **URL:** https://openjfx.io/ / https://openjdk.org/legal/gplv2+ce.html
- **Source code:** The complete corresponding source code of OpenJFX 23.0.2 is available at https://github.com/openjdk/jfx23u (tag `23.0.2+3`) and as `-sources.jar` artifacts on Maven Central (https://repo1.maven.org/maven2/org/openjfx/).
- **Modifications:** The OpenJFX classes and native libraries are redistributed unmodified.
- **Included third-party components:** OpenJFX includes the Independent JPEG Group's JPEG library, Mesa 3-D headers, and PipeWire headers; see `openjfx-jpeg_fx.md`, `openjfx-mesa3d.md`, and `openjfx-pipewire.md`.
- **Microsoft Visual C++ Runtime:** The Windows OpenJFX artifacts include the Microsoft Visual C++ Redistributable / Universal C Runtime DLLs (`vcruntime140*.dll`, `msvcp140*.dll`, `api-ms-win-*.dll`, `ucrtbase.dll`), Copyright (c) Microsoft Corporation, redistributed as part of OpenJFX under Microsoft's redistribution terms.

> This code is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License version 2 only, as published by the Free Software Foundation. Oracle designates this particular file as subject to the "Classpath" exception as provided by Oracle in the LICENSE file that accompanied this code.

---

## 2. RichTextFX and FXMisc / ReactFX Dependencies
- **RichTextFX (`org.fxmisc.richtext:richtextfx:0.11.3`):**
  - Copyright (c) 2013-2023, Tomas Mikula and contributors. All rights reserved.
  - License: BSD 2-Clause License / GPLv2 with Classpath Exception
  - URL: https://github.com/FXMisc/RichTextFX
- **Flowless (`org.fxmisc.flowless:flowless:0.7.3`):**
  - Copyright (c) 2015-2023, Tomas Mikula and contributors. All rights reserved.
  - License: BSD 2-Clause License
  - URL: https://github.com/FXMisc/Flowless
- **UndoFX (`org.fxmisc.undo:undofx:2.1.1`):**
  - Copyright (c) 2014-2020, Tomas Mikula and contributors. All rights reserved.
  - License: BSD 2-Clause License
  - URL: https://github.com/FXMisc/UndoFX
- **WellBehavedFX (`org.fxmisc.wellbehaved:wellbehavedfx:0.3.3`):**
  - Copyright (c) 2014-2020, Tomas Mikula and contributors. All rights reserved.
  - License: BSD 2-Clause License
  - URL: https://github.com/FXMisc/WellBehavedFX
- **ReactFX (`org.reactfx:reactfx:2.0-M5`):**
  - Copyright (c) 2014-2016, Tomas Mikula. All rights reserved.
  - License: BSD 2-Clause License
  - URL: https://github.com/TomasMikula/ReactFX

### BSD 2-Clause License Text
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

## 3. Apache PDFBox, FontBox, PDFBox-IO (3.0.3) & Apache Commons Logging (1.3.3)
- **Artifacts:** `org.apache.pdfbox:pdfbox:3.0.3`, `org.apache.pdfbox:fontbox:3.0.3`, `org.apache.pdfbox:pdfbox-io:3.0.3`, `commons-logging:commons-logging:1.3.3`
- **License:** Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) – full text in `Apache-2.0.txt`

### Apache PDFBox Notices
```text
Apache PDFBox
Copyright 2002-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
Based on source code contributed to the original PDFBox project.
Copyright (c) 2002-2007, www.pdfbox.org

Includes the Adobe Glyph List
Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.

Includes the Zapf Dingbats Glyph List
Copyright 2002, 2010 Adobe Systems Incorporated.

Includes the Bidi Mirroring Glyph Property (BidiMirroring-8.0.0.txt)
Copyright 1991-2015 Unicode, Inc.

Includes parts of TwelveMonkeys ImageIO
Copyright 2008-2016 Harald Kuhr

Includes the CMYK ICC-profile (CGATS001Compat-v2-micro.icc)
https://github.com/saucecontrol/Compact-ICC-Profiles
Released to the public domain under the Creative Commons CC0 license by Clinton Ingram, saucecontrol
```

### Apache FontBox Notices
```text
Apache FontBox
Copyright 2008-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
Based on source code contributed to the original FontBox project.
Copyright (c) 2006-2007, www.fontbox.org

Includes the Script Property (Scripts-10.0.0.txt)
Copyright 2017 Unicode, Inc.
```

### Apache PDFBox-IO Notices
```text
Apache PDFBox io
Copyright 2002-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

### Apache Commons Logging Notices
```text
Apache Commons Logging
Copyright 2001-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

---

## 4. OkHttp (4.12.0) & Okio (3.6.0)
- **Artifacts:** `com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okio:okio:3.6.0`, `com.squareup.okio:okio-jvm:3.6.0`
- **Copyright:** Copyright 2013-2023 Square, Inc.
- **License:** Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) – full text in `Apache-2.0.txt`
- **Public Suffix List Notice (`okhttp3/internal/publicsuffix/NOTICE`):**
  - OkHttp bundles the Public Suffix List (https://publicsuffix.org/), licensed under the Mozilla Public License Version 2.0 (https://mozilla.org/MPL/2.0/) – full text in `MPL-2.0.txt`. Its source form is available at https://github.com/publicsuffix/list.

---

## 5. Kotlin Standard Library (2.4.21) & JetBrains Annotations (13.0)
- **Artifacts:** `org.jetbrains.kotlin:kotlin-stdlib:2.4.21`, `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10`, `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10`, `org.jetbrains:annotations:13.0`
- **Copyright:** Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
- **License:** Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt)
