package com.example.flashbrowser

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.AppBarLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private val homeUrl = "https://tdsheep.tdsheepvillage.com/"
    private var currentZoom = 1.0f
    private lateinit var sharedPreferences: android.content.SharedPreferences

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnHome = findViewById(R.id.btnHome)
        btnReload = findViewById(R.id.btnReload)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)

        sharedPreferences = getSharedPreferences("BrowserSettings", MODE_PRIVATE)
        currentZoom = sharedPreferences.getFloat("zoomScale", 1.0f)

        // 設定 WebView
        webView.isNestedScrollingEnabled = true
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        
        // 允許混合內容 (解決 HTTPS 載入 HTTP 圖片問題)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val msg = it.message()
                    val level = it.messageLevel()?.name ?: "INFO"
                    val source = "${it.sourceId()}:${it.lineNumber()}"
                    android.util.Log.e("WebViewConsole", "[$level] $msg (Source: $source)")
                    
                    // 相容原本的 Logcat 過濾
                    if (msg.startsWith("[FLASH_DEBUG]")) {
                        android.util.Log.e("FLASH_DEBUG", msg)
                    }
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                // 阻擋 favicon (網頁頭像) 載入以增進效能
                if (url.endsWith("favicon.ico")) {
                    return android.webkit.WebResourceResponse("image/png", null, null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { urlInput.setText(it) }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                // 網頁載入任何資源時（極早期），搶先注入 Socket 與 HTTP 攔截器
                val js = """
                    javascript:(function() {
                        if (window.wsProxied) return;
                        window.wsProxied = true;
                        
                        // 二進位轉 ASCII 輔助函數 (用於還原 AMF3/二進位封包中的字串關鍵字)
                        function binaryToAscii(buffer) {
                            var view = new Uint8Array(buffer);
                            var str = '';
                            for (var i = 0; i < view.length; i++) {
                                var c = view[i];
                                if (c >= 32 && c <= 126) {
                                    str += String.fromCharCode(c);
                                } else {
                                    str += '.';
                                }
                            }
                            return str;
                        }

                        // 二進位轉 Hex 十六進位輔助函數
                        function toHex(buffer) {
                            var view = new Uint8Array(buffer);
                            var hex = [];
                            for (var i = 0; i < view.length; i++) {
                                var h = view[i].toString(16);
                                if (h.length < 2) h = '0' + h;
                                hex.push(h);
                            }
                            return hex.join(' ');
                        }
                        
                        // 1. 攔截 WebSocket
                        const OriginalWebSocket = window.WebSocket;
                        const WebSocketProxy = function(url, protocols) {
                            const ws = new OriginalWebSocket(url, protocols);
                            
                            const originalSend = ws.send;
                            ws.send = function(data) {
                                let detail = '';
                                if (data instanceof ArrayBuffer) {
                                    detail = new TextDecoder("utf-8").decode(data);
                                } else if (data instanceof Blob) {
                                    detail = '[Blob binary data]';
                                } else {
                                    detail = String(data);
                                }
                                detail = detail.replace(/\0/g, '');
                                return originalSend.apply(this, arguments);
                            };

                            ws.addEventListener('message', (event) => {
                                let detail = '';
                                if (event.data instanceof ArrayBuffer) {
                                    detail = new TextDecoder("utf-8").decode(event.data);
                                } else if (event.data instanceof Blob) {
                                    detail = '[Blob binary data]';
                                } else {
                                    detail = String(event.data);
                                }
                                detail = detail.replace(/\0/g, '');
                            });

                            return ws;
                        };
                        WebSocketProxy.prototype = OriginalWebSocket.prototype;
                        window.WebSocket = WebSocketProxy;

                        // 2. 攔截 XMLHttpRequest (用於 HTTP POST/AMF3)
                        const originalOpen = XMLHttpRequest.prototype.open;
                        const originalSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function() {
                            if (arguments[1] && typeof arguments[1] === 'string' && arguments[1].includes('avatar')) {
                                arguments[1] = "about:blank"; // 放棄頭像載入
                            }
                            this._url = arguments[1];
                            this._method = arguments[0];
                            return originalOpen.apply(this, arguments);
                        };
                        
                        XMLHttpRequest.prototype.send = function(data) {
                            const reqUrl = this._url;
                            const method = this._method;
                            
                            if (data) {
                                if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
                                    const buf = data.buffer || data;
                                } else if (data instanceof Blob) {
                                    const reader = new FileReader();
                                    const self = this;
                                    reader.onload = function() {
                                    };
                                    reader.readAsArrayBuffer(data);
                                } else {
                                }
                            } else {
                            }

                            this.addEventListener('load', () => {
                                try {
                                    if (this.responseType === 'arraybuffer' || this.responseType === 'blob') {
                                    } else {
                                    }
                                } catch(e) {
                                }
                            });

                            return originalSend.apply(this, arguments);
                        };

                        // 3. 攔截 Fetch 並應用 AMF3 封包修復器
                        // AMF3 反序列化讀取器
                        function AMF3Reader(buffer) {
                            let view = new Uint8Array(buffer);
                            let pos = 0;
                            let stringTable = [];
                            let objectTable = [];
                            let traitsTable = [];

                            function readByte() {
                                return view[pos++];
                            }

                            function readU29() {
                                let b = readByte();
                                if (b < 128) return b;
                                let val = (b & 0x7F) << 7;
                                b = readByte();
                                if (b < 128) return val | b;
                                val = (val | (b & 0x7F)) << 7;
                                b = readByte();
                                if (b < 128) return val | b;
                                val = (val | (b & 0x7F)) << 8;
                                b = readByte();
                                return val | b;
                            }

                            function readDouble() {
                                let dv = new DataView(view.buffer, view.byteOffset + pos, 8);
                                pos += 8;
                                return dv.getFloat64(0, false); // big endian
                            }

                            function readString() {
                                let header = readU29();
                                if ((header & 1) === 0) {
                                    return stringTable[header >> 1];
                                }
                                let len = header >> 1;
                                if (len === 0) return "";
                                let strBytes = view.subarray(pos, pos + len);
                                pos += len;
                                let str = new TextDecoder("utf-8").decode(strBytes);
                                stringTable.push(str);
                                return str;
                            }

                            function readObject() {
                                let header = readU29();
                                if ((header & 1) === 0) {
                                    return objectTable[header >> 1];
                                }
                                let inlineClass = (header & 2) === 0;
                                let traits;
                                if (inlineClass) {
                                    traits = traitsTable[header >> 2];
                                } else {
                                    let externalizable = (header & 4) !== 0;
                                    let dynamic = (header & 8) !== 0;
                                    let count = header >> 4;
                                    let className = readString();
                                    let propNames = [];
                                    for (let i = 0; i < count; i++) {
                                        propNames.push(readString());
                                    }
                                    traits = { className, propNames, externalizable, dynamic };
                                    traitsTable.push(traits);
                                }

                                let obj = {};
                                objectTable.push(obj);

                                if (traits.externalizable) {
                                    console.error("Externalizable not supported");
                                } else {
                                    for (let name of traits.propNames) {
                                        obj[name] = readValue();
                                    }
                                    if (traits.dynamic) {
                                        while (true) {
                                            let name = readString();
                                            if (!name) break;
                                            obj[name] = readValue();
                                        }
                                    }
                                }
                                return obj;
                            }

                            function readArray() {
                                let header = readU29();
                                if ((header & 1) === 0) {
                                    return objectTable[header >> 1];
                                }
                                let len = header >> 1;
                                let arr = [];
                                objectTable.push(arr);

                                let assoc = {};
                                let hasAssoc = false;
                                while (true) {
                                    let name = readString();
                                    if (!name) break;
                                    assoc[name] = readValue();
                                    hasAssoc = true;
                                }

                                for (let i = 0; i < len; i++) {
                                    arr.push(readValue());
                                }

                                if (hasAssoc) {
                                    for (let k in assoc) {
                                        arr[k] = assoc[k];
                                    }
                                }
                                return arr;
                            }

                            function readValue() {
                                let type = readByte();
                                switch (type) {
                                    case 0x00: return undefined;
                                    case 0x01: return null;
                                    case 0x02: return false;
                                    case 0x03: return true;
                                    case 0x04: return readU29();
                                    case 0x05: return readDouble();
                                    case 0x06: return readString();
                                    case 0x09: return readArray();
                                    case 0x0A: return readObject();
                                    default: return null;
                                }
                            }

                            return { readValue };
                        }

                        // AMF3 序列化寫入器
                        function AMF3Writer() {
                            let bytes = [];
                            let stringTable = [];

                            function writeByte(b) {
                                bytes.push(b);
                            }

                            function writeU29(val) {
                                if (val < 0) {
                                    val &= 0x1FFFFFFF;
                                }
                                if (val < 0x80) {
                                    writeByte(val);
                                } else if (val < 0x4000) {
                                    writeByte(((val >> 7) & 0x7F) | 0x80);
                                    writeByte(val & 0x7F);
                                } else if (val < 0x200000) {
                                    writeByte(((val >> 14) & 0x7F) | 0x80);
                                    writeByte(((val >> 7) & 0x7F) | 0x80);
                                    writeByte(val & 0x7F);
                                } else {
                                    writeByte(((val >> 22) & 0x7F) | 0x80);
                                    writeByte(((val >> 15) & 0x7F) | 0x80);
                                    writeByte(((val >> 8) & 0x7F) | 0x80);
                                    writeByte(val & 0xFF);
                                }
                            }

                            function writeDouble(val) {
                                let buf = new ArrayBuffer(8);
                                new DataView(buf).setFloat64(0, val, false);
                                let view = new Uint8Array(buf);
                                for (let i = 0; i < 8; i++) {
                                    writeByte(view[i]);
                                }
                            }

                            function writeString(str) {
                                if (str === "") {
                                    writeU29(1);
                                    return;
                                }
                                let idx = stringTable.indexOf(str);
                                if (idx !== -1) {
                                    writeU29(idx << 1);
                                    return;
                                }
                                stringTable.push(str);
                                let strBytes = new TextEncoder("utf-8").encode(str);
                                writeU29((strBytes.length << 1) | 1);
                                for (let i = 0; i < strBytes.length; i++) {
                                    writeByte(strBytes[i]);
                                }
                            }

                            function writeValue(val) {
                                if (val === undefined) {
                                    writeByte(0x00);
                                } else if (val === null) {
                                    writeByte(0x01);
                                } else if (val === false) {
                                    writeByte(0x02);
                                } else if (val === true) {
                                    writeByte(0x03);
                                } else if (typeof val === "number") {
                                    if (Number.isInteger(val) && val >= -0x10000000 && val <= 0x0FFFFFFF) {
                                        writeByte(0x04);
                                        writeU29(val);
                                    } else {
                                        writeByte(0x05);
                                        writeDouble(val);
                                    }
                                } else if (typeof val === "string") {
                                    writeByte(0x06);
                                    writeString(val);
                                } else if (Array.isArray(val)) {
                                    writeByte(0x09);
                                    // 強制輸出為連續陣列 (LSB=1 表示 Inline，長度 << 1)
                                    writeU29((val.length << 1) | 1);
                                    writeString(""); // 關閉動態 key 區段 (空字串)
                                    for (let i = 0; i < val.length; i++) {
                                        writeValue(val[i]);
                                    }
                                } else if (typeof val === "object") {
                                    writeByte(0x0A);
                                    let keys = Object.keys(val);
                                    writeU29((0 << 4) | 0x0B); // 0個靜態屬性, 動態屬性=true, Inline=true
                                    writeString(""); // 類別名稱為空
                                    for (let k of keys) {
                                        writeString(k);
                                        writeValue(val[k]);
                                    }
                                    writeString(""); // 結束動態屬性
                                }
                            }

                            return { writeValue, getBytes: () => new Uint8Array(bytes) };
                        }

                        // AMF3 封包修改核心
                        function patchAMF3Request(buffer) {
                            try {
                                let view = new Uint8Array(buffer);
                                let pos = 0;
                                let version = (view[pos++] << 8) | view[pos++];
                                let headerCount = (view[pos++] << 8) | view[pos++];
                                if (headerCount > 0) {
                                    // Cannot easily skip AMF0 headers without a full AMF0 parser, but let's hope there are none.
                                }
                                let msgCount = (view[pos++] << 8) | view[pos++];
                                
                                let targetURILen = (view[pos++] << 8) | view[pos++];
                                pos += targetURILen;
                                let responseURILen = (view[pos++] << 8) | view[pos++];
                                pos += responseURILen;
                                let bodyLenPos = pos;
                                pos += 4; // body length
                                
                                
                                // AMF0 呼叫的參數會被封裝在一個 Strict Array (0x0A) 中，後面接著 4 位元組的陣列長度 (通常為 1)
                                let amf0ArrayMarker = view[pos];
                                if (amf0ArrayMarker === 0x0A) {
                                    pos += 1 + 4; // 跳過 0x0A 與 4 位元組長度
                                }
                                
                                let amf3Marker = view[pos];
                                if (amf3Marker !== 0x11) {
                                    return buffer;
                                }
                                pos += 1;

                                let amf3Buffer = buffer.slice(pos);
                                let reader = AMF3Reader(amf3Buffer);
                                let obj = reader.readValue();

                                let writer = AMF3Writer();
                                writer.writeValue(obj);
                                let newAmf3Bytes = writer.getBytes();

                                // 重建 AMF0 Remoting 封包
                                let headerBytes = new Uint8Array(buffer.slice(0, bodyLenPos));
                                // 封包長度 = 表頭 + 4位元組長度值 + 5位元組的 AMF0 陣列包裝 + 1位元組的 0x11 標記 + 新 AMF3 長度
                                let newPacket = new Uint8Array(headerBytes.length + 4 + 5 + 1 + newAmf3Bytes.length);
                                newPacket.set(headerBytes, 0);

                                // 寫入新的 body 長度 (新 AMF3 長度 + 1位元組 0x11 + 5位元組 AMF0 Array)
                                let newBodyLen = newAmf3Bytes.length + 6;
                                let dv = new DataView(newPacket.buffer);
                                dv.setUint32(bodyLenPos, newBodyLen, false); // big-endian

                                // 寫入 AMF0 Strict Array 標記與長度 1
                                newPacket[bodyLenPos + 4] = 0x0A;
                                newPacket[bodyLenPos + 5] = 0x00;
                                newPacket[bodyLenPos + 6] = 0x00;
                                newPacket[bodyLenPos + 7] = 0x00;
                                newPacket[bodyLenPos + 8] = 0x01;

                                // 寫入 AMF3 轉換標記
                                newPacket[bodyLenPos + 9] = 0x11;
                                
                                // 寫入新 AMF3 資料
                                newPacket.set(newAmf3Bytes, bodyLenPos + 10);

                                return newPacket.buffer;
                            } catch(e) {
                                return buffer;
                            }
                        }

                        // AMF0 修正陣列 (將 ECMAArray 0x08 改為 Strict Array 0x0A)
                        function patchAMF0Arrays(buffer) {
                            try {
                                let view = new Uint8Array(buffer);
                                let patterns = [
                                    [0x00, 0x04, 0x77, 0x69, 0x64, 0x73], // wids
                                    [0x00, 0x09, 0x73, 0x6c, 0x61, 0x76, 0x65, 0x5f, 0x69, 0x64, 0x73] // slave_ids
                                ];
                                
                                for (let p = 0; p < patterns.length; p++) {
                                    let pattern = patterns[p];
                                    let pos = -1;
                                    for (let i = 0; i < view.length - pattern.length; i++) {
                                        let match = true;
                                        for (let j = 0; j < pattern.length; j++) {
                                            if (view[i+j] !== pattern[j]) {
                                                match = false; break;
                                            }
                                        }
                                        if (match) {
                                            pos = i + pattern.length;
                                            break;
                                        }
                                    }
                                    if (pos === -1) continue;
                                    
                                    if (view[pos] === 0x08) { // ECMAArray
                                        let arrLen = (view[pos+1] << 24) | (view[pos+2] << 16) | (view[pos+3] << 8) | view[pos+4];
                                        
                                        let newBuffer = new Uint8Array(buffer.byteLength);
                                        newBuffer.set(view.subarray(0, pos), 0);
                                        
                                        let newPos = pos;
                                        newBuffer[newPos++] = 0x0A; // Strict Array
                                        newBuffer[newPos++] = view[pos+1];
                                        newBuffer[newPos++] = view[pos+2];
                                        newBuffer[newPos++] = view[pos+3];
                                        newBuffer[newPos++] = view[pos+4];
                                        
                                        let oldPos = pos + 5;
                                        for (let i = 0; i < arrLen; i++) {
                                            let keyLen = (view[oldPos] << 8) | view[oldPos+1];
                                            oldPos += 2 + keyLen; // Skip key
                                            
                                            let type = view[oldPos];
                                            if (type === 0x02) { // String
                                                let strLen = (view[oldPos+1] << 8) | view[oldPos+2];
                                                let valueLen = 1 + 2 + strLen;
                                                newBuffer.set(view.subarray(oldPos, oldPos + valueLen), newPos);
                                                newPos += valueLen;
                                                oldPos += valueLen;
                                            } else if (type === 0x00) { // Number
                                                let valueLen = 1 + 8;
                                                newBuffer.set(view.subarray(oldPos, oldPos + valueLen), newPos);
                                                newPos += valueLen;
                                                oldPos += valueLen;
                                            } else {
                                                return buffer; // abort patching
                                            }
                                        }
                                        
                                        // check ObjectEnd
                                        if (view[oldPos] === 0x00 && view[oldPos+1] === 0x00 && view[oldPos+2] === 0x09) {
                                            oldPos += 3;
                                        } else {
                                        }
                                        
                                        let restLen = view.length - oldPos;
                                        newBuffer.set(view.subarray(oldPos), newPos);
                                        
                                        let finalBuffer = newBuffer.slice(0, newPos + restLen);
                                        
                                        let vPos = 0;
                                        let version = (finalBuffer[vPos++] << 8) | finalBuffer[vPos++];
                                        let headerCount = (finalBuffer[vPos++] << 8) | finalBuffer[vPos++];
                                        if (headerCount === 0) {
                                            let msgCount = (finalBuffer[vPos++] << 8) | finalBuffer[vPos++];
                                            let targetURILen = (finalBuffer[vPos++] << 8) | finalBuffer[vPos++];
                                            vPos += targetURILen;
                                            let responseURILen = (finalBuffer[vPos++] << 8) | finalBuffer[vPos++];
                                            vPos += responseURILen;
                                            let bodyLenPos = vPos;
                                            
                                            let oldBodyLen = (finalBuffer[bodyLenPos] << 24) | (finalBuffer[bodyLenPos+1] << 16) | (finalBuffer[bodyLenPos+2] << 8) | finalBuffer[bodyLenPos+3];
                                            let sizeDiff = view.length - finalBuffer.length;
                                            let newBodyLen = oldBodyLen - sizeDiff;
                                            
                                            let dv = new DataView(finalBuffer.buffer);
                                            dv.setUint32(bodyLenPos, newBodyLen, false);
                                        }
                                        
                                        buffer = finalBuffer.buffer;
                                        view = new Uint8Array(buffer);
                                    }
                                }
                                return buffer;
                            } catch(e) {
                                return buffer;
                            }
                        }

                        const originalFetch = window.fetch;
                        window.fetch = function(resource, init) {
                            let reqUrl = '';
                            let reqMethod = 'GET';
                            
                            if (typeof resource === 'string') {
                                reqUrl = resource;
                                reqMethod = (init && init.method) || 'GET';
                            } else if (resource && resource instanceof Request) {
                                reqUrl = resource.url;
                                reqMethod = resource.method;
                            } else if (resource) {
                                reqUrl = String(resource);
                            }
                            
                            if (reqUrl.includes('avatar')) {
                                return Promise.reject(new Error("Avatar loading abandoned for performance"));
                            }

                            // 1. 如果是 Request 物件且發往 gateway/
                            if (resource && resource instanceof Request && resource.url.includes('/gateway/') && resource.method === 'POST') {
                                return (async function() {
                                    try {
                                        const arrayBuffer = await resource.clone().arrayBuffer();
                                        if (arrayBuffer.byteLength > 0) {
                                        }
                                        let patchedBuffer = patchAMF3Request(arrayBuffer);
                                        if (patchedBuffer === arrayBuffer || patchedBuffer.byteLength === arrayBuffer.byteLength) {
                                            patchedBuffer = patchAMF0Arrays(patchedBuffer);
                                        }
                                        const newRequest = new Request(resource, {
                                            body: patchedBuffer
                                        });
                                        return originalFetch.call(window, newRequest, init);
                                    } catch(e) {
                                        return originalFetch.apply(this, arguments);
                                    }
                                })();
                            }

                            // 2. 如果是網址字串且發往 gateway/
                            if (typeof resource === 'string' && resource.includes('/gateway/') && init && init.method === 'POST' && init.body) {
                                return (async function() {
                                    try {
                                        let arrayBuffer = null;
                                        if (init.body instanceof ArrayBuffer) {
                                            arrayBuffer = init.body;
                                        } else if (ArrayBuffer.isView(init.body)) {
                                            arrayBuffer = init.body.buffer;
                                        } else if (init.body instanceof Blob) {
                                            arrayBuffer = await init.body.arrayBuffer();
                                        }
                                        
                                        if (arrayBuffer) {
                                            let patchedBuffer = patchAMF3Request(arrayBuffer);
                                            if (patchedBuffer === arrayBuffer || patchedBuffer.byteLength === arrayBuffer.byteLength) {
                                                patchedBuffer = patchAMF0Arrays(patchedBuffer);
                                            }
                                            init.body = patchedBuffer;
                                        }
                                    } catch(e) {
                                    }
                                    return originalFetch.call(window, resource, init);
                                })();
                            }
                            

                            // 非同步擷取請求 Body (唯獨記錄用)
                            if (resource && resource instanceof Request) {
                                try {
                                    const clone = resource.clone();
                                    clone.arrayBuffer().then(buf => {
                                        if (buf.byteLength > 0) {
                                        }
                                    }).catch(e => {
                                    });
                                } catch(e) {}
                            } else if (init && init.body) {
                                let body = init.body;
                                if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
                                    const buf = body.buffer || body;
                                } else {
                                }
                            }

                            return originalFetch.apply(this, arguments).then(response => {
                                try {
                                    const clone = response.clone();
                                    clone.arrayBuffer().then(buf => {
                                        if (buf.byteLength > 0) {
                                        }
                                    }).catch(e => {});
                                } catch(e) {}
                                return response;
                            }).catch(err => {
                                throw err;
                            });
                        };

                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 網頁載入完成後，強制注入 Ruffle 引擎
                injectRuffle(view)
                applyJsZoom()
            }
        }

        val appBarLayout = findViewById<AppBarLayout>(R.id.appBarLayout)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0f
        var isAppBarVisible = true

        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startY
                    if (deltaY > touchSlop && !isAppBarVisible) {
                        // 向下劃動 (手指往下拉)，顯示導覽列
                        appBarLayout.setExpanded(true, true)
                        isAppBarVisible = true
                        startY = event.rawY
                    } else if (deltaY < -touchSlop && isAppBarVisible) {
                        // 向上劃動 (手指往上推)，隱藏導覽列
                        appBarLayout.setExpanded(false, true)
                        isAppBarVisible = false
                        startY = event.rawY
                    } else if ((deltaY > 0 && isAppBarVisible) || (deltaY < 0 && !isAppBarVisible)) {
                        // 狀態符合預期時，持續更新 startY，避免反向滑動時需要抵銷累積的誤差距離
                        startY = event.rawY
                    }
                }
            }
            false
        }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadUrlFromInput()
                true
            } else {
                false
            }
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }

        btnHome.setOnClickListener {
            webView.loadUrl(homeUrl)
        }

        btnReload.setOnClickListener {
            webView.reload()
        }

        btnZoomIn.setOnClickListener {
            if (currentZoom < 0.5f) {
                currentZoom += 0.05f
            } else {
                currentZoom += 0.1f
            }
            sharedPreferences.edit().putFloat("zoomScale", currentZoom).apply()
            applyJsZoom()
        }

        btnZoomOut.setOnClickListener {
            if (currentZoom <= 0.5f) {
                currentZoom -= 0.05f
            } else {
                currentZoom -= 0.1f
            }
            if (currentZoom < 0.01f) currentZoom = 0.01f
            sharedPreferences.edit().putFloat("zoomScale", currentZoom).apply()
            applyJsZoom()
        }

        // 載入預設進入頁面 (首頁)
        if (savedInstanceState == null) {
            webView.loadUrl(homeUrl)
        }
    }

    private fun loadUrlFromInput() {
        var url = urlInput.text.toString().trim()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            webView.loadUrl(url)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun applyJsZoom() {
        val zoomStr = String.format(java.util.Locale.US, "%.2f", currentZoom)
        val js = """
            javascript:(function() {
                // 清除可能殘留的 CSS zoom，避免干擾 Canvas 點擊座標
                document.body.style.zoom = '';
                
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                
                // 瞬間鎖定最大與最小縮放比例，強迫 WebView 進行原生縮放
                meta.setAttribute('content', 'width=device-width, initial-scale=' + $zoomStr + ', minimum-scale=' + $zoomStr + ', maximum-scale=' + $zoomStr + ', user-scalable=yes');
                
                // 稍微延遲後解除鎖定，允許使用者繼續使用雙指縮放
                setTimeout(function() {
                    meta.setAttribute('content', 'width=device-width, initial-scale=' + $zoomStr + ', minimum-scale=0.01, maximum-scale=5.0, user-scalable=yes');
                }, 100);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectRuffle(view: WebView?) {
        // 這段 JS 會在網頁中動態插入 Ruffle 的腳本，並啟動模擬器
        val js = """
            javascript:(function() {
                // 解鎖 Viewport 限制以允許更小極限縮放
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=device-width, initial-scale=1.0, minimum-scale=0.01, maximum-scale=5.0, user-scalable=yes');

                // 攔截 Socket 連線
                const OriginalWebSocket = window.WebSocket;
                const WebSocketProxy = function(url, protocols) {
                    const ws = new OriginalWebSocket(url, protocols);
                    
                    const originalSend = ws.send;
                    ws.send = function(data) {
                        let detail = '';
                        if (data instanceof ArrayBuffer) {
                            detail = new TextDecoder("utf-8").decode(data);
                        } else if (data instanceof Blob) {
                            detail = '[Blob binary data]';
                        } else {
                            detail = String(data);
                        }
                        detail = detail.replace(/\0/g, '');
                        return originalSend.apply(this, arguments);
                    };

                    ws.addEventListener('message', (event) => {
                        let detail = '';
                        if (event.data instanceof ArrayBuffer) {
                            detail = new TextDecoder("utf-8").decode(event.data);
                        } else if (event.data instanceof Blob) {
                            detail = '[Blob binary data]';
                        } else {
                            detail = String(event.data);
                        }
                        detail = detail.replace(/\0/g, '');
                    });

                    return ws;
                };
                WebSocketProxy.prototype = OriginalWebSocket.prototype;
                window.WebSocket = WebSocketProxy;

                if (window.ruffleInjected) return;
                window.ruffleInjected = true;
                
                window.RufflePlayer = window.RufflePlayer || {};
                window.RufflePlayer.config = {
                    autoplay: "on",
                    unmuteOverlay: "hidden",
                    splashScreen: false, // 關閉 Ruffle 頭像/啟動畫面
                    fontSources: ["https://cdn.jsdelivr.net/gh/lxgw/LxgwWenKai-Lite@main/fonts/TTF/LXGWWenKaiLite-Regular.ttf"],
                    defaultFonts: {
                        sans: ["LXGW WenKai Lite", "LXGW WenKai Lite Regular", "sans"],
                        serif: ["LXGW WenKai Lite", "LXGW WenKai Lite Regular", "serif"],
                        typewriter: ["LXGW WenKai Lite", "LXGW WenKai Lite Regular", "typewriter"]
                    }
                };

                var script = document.createElement('script');
                script.src = 'https://unpkg.com/@ruffle-rs/ruffle';
                document.head.appendChild(script);
            })();
        """.trimIndent()
        
        view?.evaluateJavascript(js, null)
    }
}
