
// ============ 状态变量 ============
var canvas = document.getElementById('readerCanvas');
var ctx = canvas.getContext('2d');

var currentContent = '';
var chapterTitle = '';
var currentChapterIndex = -1;
var pages = [];
var pageStartOffsets = [];
var currentPage = 0;

// 默认字体大小（根据屏幕宽度动态调整）
var fontSize = Math.max(14, Math.floor(window.innerWidth / 24));
var lineHeightRatio = 1.8;
var padding = 16; // 左右边距
var topPadding = padding + fontSize; // 顶部边距 = 左右边距 + 整行高 = 半行边距 + 半行额外留白

var bgColor = '#FFFFFF';
var textColor = '#1D1D1F';
var bgTextureImg = null; // Image for texture backgrounds
var fontFamily = '-apple-system, "SF Pro Text", "PingFang SC", sans-serif'; // 当前字体（iOS 系统字体）
var titleFontSize = fontSize + 4;

// 启动期"待应用"的字体/纹理（由 Android 注入 init script 设置）
var _pendingFontPath = null;
var _pendingFontName = null;
var _pendingTexture = null;
var _pendingResourcesLoading = false;

/**
 * ✅ 立即启动加载待加载的字体/纹理资源（由 init script 调用）
 * 在 HTML 加载完成时就开始，使后续 loadContent 调用时字体/纹理可能已经就绪
 */
function startPendingResources() {
    // 字体
    if (_pendingFontPath && _pendingFontName) {
        var fp = _pendingFontPath, fn = _pendingFontName;
        try {
            var font = new FontFace(fn, 'url(' + fp + ')');
            font.load().then(function(loaded) {
                document.fonts.add(loaded);
                fontFamily = fn;
                _pendingFontPath = null;
                _pendingFontName = null;
                // 如果已经有内容，字体加载完成后重新分页并重绘
                if (currentContent) {
                    paginate(currentContent, chapterTitle);
                    drawPage(currentPage);
                    if (pageTurnMode === 'simulation' && simulationActive) {
                        destroySimulation();
                        initSimulation();
                    }
                    notifyJavaPageChange();
                }
            }).catch(function(err) {
                console.error('字体加载失败: ' + err);
                _pendingFontPath = null;
                _pendingFontName = null;
            });
        } catch (e) {
            console.error('字体初始化失败: ' + e);
            _pendingFontPath = null;
            _pendingFontName = null;
        }
    }

    // 纹理
    if (_pendingTexture) {
        var tx = _pendingTexture;
        var img = new Image();
        img.onload = function() {
            bgTextureImg = img;
            _pendingTexture = null;
            // ✅ 如果在仿真模式（含占位模式），纹理加载完成时同步所有容器/背面页背景
            if (pageTurnMode === 'simulation') {
                syncSimulationBackground();
                if (_simulationPlaceholderActive) {
                    // 占位页也更新为纹理背景
                    $('#flipbook .page-div').css('background-image', 'url(' + img.src + ')');
                    $('#flipbook .page-div').css('background-size', '100% 100%');
                }
            }
            if (currentContent) {
                drawPage(currentPage);
                // ✅ 仿真模式激活时重建 flipbook，使已缓存的页面位图重新带纹理渲染
                if (simulationActive) reinitSimulation();
            }
        };
        img.onerror = function() {
            console.error('纹理加载失败: ' + tx);
            _pendingTexture = null;
        };
        img.src = tx;
    }
}

var isNightMode = false;

// 页眉页脚设置
var showHeaderFooter = true;
var headerFooterFontSize = 12; // 初始字号改为12
var headerFooterColor = '#8E8E93'; // iOS 次级灰

// 电量时间显示
var showBatteryTime = false;
var batteryText = '';
var timeText = '';

// 翻页动画模式: 'none','cover','slide','updown','fade','simulation'
var pageTurnMode = 'cover';
var isAnimating = false;
var animWatchdog = null; // 动画超时保护

// turn.js 仿真翻页状态
var simulationActive = false;
var simPageCache = {};
var renderCanvas = null;
var renderCtx = null;
var _deferDraw = false;
var _reinitTimer = null;

// 本地书和阅读进度
var isLocalBook = false;
var bookProgress = 0;

// ============ 仿真翻页背景同步 ============
/**
 * ✅ 将当前背景色 + 纹理统一同步到仿真翻页的所有相关元素。
 * 关键点：turn.js 在翻页开始时才动态创建书页背面（div.page.p-temporal，
 * 即 pageObjs[0]），因此必须用样式表规则（而非内联样式）写入，
 * 这样延迟创建的背面页也能自动继承纹理背景，保证正反书页一致。
 */
function syncSimulationBackground() {
    var styleEl = document.getElementById('simBgStyle');
    if (!styleEl) return;

    var hasTexture = !!(bgTextureImg && bgTextureImg.complete && bgTextureImg.src);
    var texUrl = hasTexture ? 'url(' + bgTextureImg.src + ')' : 'none';

    // turn.js 4.1.0 单页模式相关元素：
    //   #flipbook .page-wrapper      —— 每页的外层 wrapper
    //   #flipbook .page.p-temporal   —— 翻页时动态创建/复用的书页背面（pageObjs[0]），
    //                                  会在首次翻页后才存在于 DOM，且可能残留历史内联样式，
    //                                  故必须用 !important 样式表规则覆盖。
    // 注意：不能对 .page 一刀切——内容页 .page-div 也会被 turn.js 加上 page 类，
    //       其内联 background-image 是渲染好的页面内容图，不可被纹理覆盖。
    var selectors = '#flipbookContainer, #flipbook, ' +
                    '#flipbook .page-wrapper, ' +
                    '#flipbook .turn-page-wrapper, #flipbook .turn-page, ' +
                    '#flipbook .page.p-temporal';

    var css = '';
    // 1. 统一底色（纹理之下的兜底色）
    css += selectors + ' { background-color: ' + bgColor + ' !important; }\n';
    // 2. 纹理背景：容器 + wrapper + 书页背面，保证正反书页背景一致
    css += selectors + ' { background-image: ' + texUrl + ' !important; ' +
           'background-size: 100% 100% !important; ' +
           'background-repeat: no-repeat !important; }\n';

    styleEl.textContent = css;
}

// ============ 初始化 Canvas 尺寸 ============
function resizeCanvas() {
    var dpr = window.devicePixelRatio || 1;
    canvas.width = window.innerWidth * dpr;
    canvas.height = window.innerHeight * dpr;
    // 设置 CSS 尺寸（已通过样式 100% 覆盖，这里再确保）
    canvas.style.width = window.innerWidth + 'px';
    canvas.style.height = window.innerHeight + 'px';
    ctx.setTransform(1, 0, 0, 1, 0, 0); // 重置变换
    ctx.scale(dpr, dpr);
    if (currentContent) {
        paginate(currentContent, chapterTitle);
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

window.addEventListener('resize', resizeCanvas);

// ============ 分页核心 ============
function paginate(text, title) {
    pages = [];
    pageStartOffsets = [];
    if (!text && !title) return;

    var maxWidth = window.innerWidth - padding * 2;
    // 页脚固定在 window.innerHeight - 15 处，预留少量间距
    var footerReserved = 17;
    // 可用内容高度 = 屏幕高度 - 顶部padding - 底部页脚预留
    var contentAreaHeight = window.innerHeight - topPadding - footerReserved;
    var maxHeight = contentAreaHeight;
    
    // 如果显示页眉，需要减去页眉占用的空间
    var headerOffset = 0;
    if (showHeaderFooter && title) {
        headerOffset = headerFooterFontSize + 10 + Math.floor(fontSize / 2); // 页眉高度 + 间距(10px) + 正文与页眉之间再加半行
        maxHeight -= headerOffset;
    }
    
    var currentPageLines = [];
    var currentPageHeight = 0;
    var globalOffset = 0;

    // 处理标题
    if (title) {
        ctx.font = 'bold ' + titleFontSize + 'px ' + fontFamily;
        var titleLines = splitTextToLines(title, maxWidth);
        titleLines.forEach(function(line) {
            var h = titleFontSize * 1.6;
            if (currentPageHeight + h > maxHeight) {
                pages.push(currentPageLines.slice());
                pageStartOffsets.push(globalOffset);
                currentPageLines = [];
                currentPageHeight = 0;
            }
            currentPageLines.push({text: line, fontSize: titleFontSize, bold: true, color: textColor});
            currentPageHeight += h;
        });
        // 标题后空行
        var blankH = titleFontSize * 0.8;
        if (currentPageHeight + blankH > maxHeight) {
            pages.push(currentPageLines.slice());
            pageStartOffsets.push(globalOffset);
            currentPageLines = [];
            currentPageHeight = 0;
        }
        currentPageHeight += blankH;
    }

    // 处理正文
    ctx.font = fontSize + 'px ' + fontFamily;
    var indent = '\u3000\u3000'; // 两个全角空格（首行缩进）
    var paragraphs = text.split('\n');
    paragraphs.forEach(function(para, paraIndex) {
        if (para.length === 0) {
            // 空行：段落间距
            var h = fontSize * lineHeightRatio * 0.5;
            if (currentPageHeight + h > maxHeight) {
                pages.push(currentPageLines.slice());
                pageStartOffsets.push(globalOffset);
                currentPageLines = [];
                currentPageHeight = 0;
            }
            currentPageHeight += h;
            globalOffset += 1;
            return;
        }

        // 去除段首已有缩进（全角空格、半角空格），再统一添加两格缩进
        var trimmed = para.replace(/^[\s\u3000]+/, '');
        var indentedPara = indent + trimmed;
        var lines = splitTextToLines(indentedPara, maxWidth);
        lines.forEach(function(line) {
            var h = fontSize * lineHeightRatio;
            if (currentPageHeight + h > maxHeight) {
                pages.push(currentPageLines.slice());
                pageStartOffsets.push(globalOffset);
                currentPageLines = [];
                currentPageHeight = 0;
            }
            currentPageLines.push({text: line, fontSize: fontSize, bold: false, color: textColor});
            currentPageHeight += h;
        });
        globalOffset += para.length + 1;
    });

    if (currentPageLines.length > 0) {
        pages.push(currentPageLines.slice());
        pageStartOffsets.push(globalOffset);
    }
}

function splitTextToLines(text, maxWidth) {
    var lines = [];
    var currentLine = '';
    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        var testLine = currentLine + ch;
        var metrics = ctx.measureText(testLine);
        if (metrics.width > maxWidth && currentLine.length > 0) {
            lines.push(currentLine);
            currentLine = ch;
        } else {
            currentLine = testLine;
        }
    }
    if (currentLine.length > 0) {
        lines.push(currentLine);
    }
    return lines;
}

// ============ 绘制指定页 ============
function drawPage(pageIndex) {
    ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
    ctx.fillStyle = bgColor;
    ctx.fillRect(0, 0, window.innerWidth, window.innerHeight);
    if (bgTextureImg && bgTextureImg.complete) {
        ctx.drawImage(bgTextureImg, 0, 0, window.innerWidth, window.innerHeight);
    }

    if (pageIndex < 0 || pageIndex >= pages.length) return;

    var lines = pages[pageIndex];
    var y = topPadding;
    var maxY = window.innerHeight - 17; // 页脚预留空间，与分页计算一致
    
    // 绘制页眉（如果启用）
    if (showHeaderFooter && chapterTitle) {
        ctx.font = headerFooterFontSize + 'px ' + fontFamily;
        ctx.fillStyle = headerFooterColor; // 使用浅灰色
        ctx.textAlign = 'left'; // 左对齐
        ctx.fillText(chapterTitle, padding, topPadding + headerFooterFontSize);
        y = topPadding + headerFooterFontSize + 10 + Math.floor(fontSize / 2); // 页眉后再加 10px + 半行间距
    }
    
    lines.forEach(function(line) {
        var lineH = line.fontSize * (line.bold ? 1.6 : lineHeightRatio);
        // 检查是否超出底部边界
        if (y + lineH > maxY) {
            return; // 跳过超出的行
        }
        
        if (line.bold) {
            ctx.font = 'bold ' + line.fontSize + 'px ' + fontFamily;
        } else {
            ctx.font = line.fontSize + 'px ' + fontFamily;
        }
        ctx.fillStyle = line.color || textColor;
        ctx.fillText(line.text, padding, y + line.fontSize);
        y += lineH;
    });
    
    //  绘制页脚（如果启用）
    if (showHeaderFooter && pages.length > 0) {
        var footerText = (pageIndex + 1) + '/' + pages.length;
        
        ctx.font = headerFooterFontSize + 'px ' + fontFamily;
        ctx.fillStyle = headerFooterColor; // 使用浅灰色
        
        // 左侧：电量和时间
        if (showBatteryTime && (batteryText || timeText)) {
            var leftInfo = batteryText + (batteryText && timeText ? '  ' : '') + timeText;
            ctx.textAlign = 'left';
            ctx.fillText(leftInfo, padding, window.innerHeight - 15);
        }
        
        // 右侧：页码
        // 本地书显示阅读百分比
        if (typeof isLocalBook !== 'undefined' && isLocalBook && typeof bookProgress !== 'undefined') {
            var progressText = ' ' + bookProgress + '%';
            ctx.textAlign = 'right'; // 右对齐
            ctx.fillText(footerText + progressText, window.innerWidth - padding, window.innerHeight - 15);
        } else {
            ctx.textAlign = 'right'; // 右对齐
            ctx.fillText(footerText, window.innerWidth - padding, window.innerHeight - 15);
        }
        ctx.textAlign = 'left'; // 重置对齐方式
    }
}

// ============ 翻页控制 ============
function goToPage(page) {
    if (isAnimating) return;
    if (page < 0) page = 0;
    if (page >= pages.length) page = pages.length - 1;
    if (page === currentPage) return;
    if (simulationActive) {
        $('#flipbook').turn('page', page + 1);
        return;
    }
    var oldPage = currentPage;
    currentPage = page;
    if (pageTurnMode === 'none') {
        drawPage(currentPage);
        notifyJavaPageChange();
    } else {
        animateTurn(oldPage, currentPage, page > oldPage);
    }
}

function nextPage() {
    if (isAnimating) return;
    try {
        if (simulationActive) {
            // 最后一页之后存在"章节结束页"（totalPages = pages.length + 1），
            // 点击翻页先翻到结束页（保留仿真翻页动画），turned 后由 onChapterEnd 进入下一章
            if (currentPage < pages.length) {
                $('#flipbook').turn('next');
            } else {
                try { Android.onChapterEnd(); } catch(e) {}
            }
            return;
        }
        if (currentPage < pages.length - 1) {
            var oldPage = currentPage;
            currentPage++;
            if (pageTurnMode === 'none') {
                drawPage(currentPage);
                notifyJavaPageChange();
            } else {
                animateTurn(oldPage, currentPage, true);
            }
        } else {
            Android.onChapterEnd();
        }
    } catch (e) {
        console.error('nextPage异常: ' + e.message);
        isAnimating = false;
    }
}

function prevPage() {
    if (isAnimating) return;
    try {
        if (simulationActive) {
            if (currentPage > 0) {
                $('#flipbook').turn('previous');
            } else {
                try { Android.onChapterStart(); } catch(e) {}
            }
            return;
        }
        if (currentPage > 0) {
            var oldPage = currentPage;
            currentPage--;
            if (pageTurnMode === 'none') {
                drawPage(currentPage);
                notifyJavaPageChange();
            } else {
                animateTurn(oldPage, currentPage, false);
            }
        } else {
            try { Android.onChapterStart(); } catch(e) {}
        }
    } catch (e) {
        console.error('prevPage异常: ' + e.message);
        isAnimating = false;
    }
}

// ✅ 新增：获取当前页的文本内容（用于书签预览）
function getCurrentPageText() {
    if (currentPage < 0 || currentPage >= pages.length) return "";
    
    var lines = pages[currentPage];
    if (!lines || lines.length === 0) return "";
    
    // 提取所有行的文本，用换行符连接
    var textLines = [];
    for (var i = 0; i < lines.length; i++) {
        if (lines[i].text && lines[i].text.trim() !== "") {
            textLines.push(lines[i].text);
        }
    }
    
    return textLines.join("\n");
}

function notifyJavaPageChange() {
    try {
        Android.onPageChanged(currentPage + 1, pages.length);
    } catch(e) {}
}

// ============ 翻页动画系统 ============
var ANIM_DURATION = 280;

function setPageTurnMode(mode) {
    var wasSimulation = simulationActive || _simulationPlaceholderActive;
    pageTurnMode = mode;
    if (mode === 'simulation') {
        if (!wasSimulation) {
            if (currentContent) {
                initSimulation();
            } else {
                // 还没有章节内容：先展示占位书本，等 loadContent 到达再重建
                showSimulationPlaceholder();
            }
        }
    } else {
        if (wasSimulation) {
            destroySimulation();
        }
    }
}

function animateTurn(fromPage, toPage, isForward) {
    isAnimating = true;
    // 超时保护：2秒后强制恢复
    if (animWatchdog) clearTimeout(animWatchdog);
    animWatchdog = setTimeout(function() {
        if (isAnimating) {
            console.warn('翻页动画超时，强制恢复');
            isAnimating = false;
        }
    }, 2000);
    var dpr = window.devicePixelRatio || 1;
    var CW = canvas.width;   // 实际像素宽
    var CH = canvas.height;  // 实际像素高
    var W = window.innerWidth;  // CSS像素宽
    var H = window.innerHeight; // CSS像素高

    var tmpNew, tmpOld;
    try {
        // 截取旧页画面（用实际像素尺寸）
        var savedPage = currentPage;
        currentPage = fromPage;
        var oldImg = ctx.getImageData(0, 0, CW, CH);
        // 截取新页画面
        currentPage = toPage;
        drawPage(toPage);
        var newImg = ctx.getImageData(0, 0, CW, CH);
        currentPage = savedPage;

        // 将新页像素放入离屏 canvas（同样用实际像素尺寸）
        tmpNew = document.createElement('canvas');
        tmpNew.width = CW;
        tmpNew.height = CH;
        tmpNew.getContext('2d').putImageData(newImg, 0, 0);

        // 将旧页像素放入离屏 canvas
        tmpOld = document.createElement('canvas');
        tmpOld.width = CW;
        tmpOld.height = CH;
        tmpOld.getContext('2d').putImageData(oldImg, 0, 0);
    } catch (e) {
        console.error('翻页截图异常: ' + e.message);
        // 截图失败，直接跳到目标页
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.scale(dpr, dpr);
        currentPage = toPage;
        drawPage(currentPage);
        notifyJavaPageChange();
        isAnimating = false;
        return;
    }

    var startTime = null;
    function frame(timestamp) {
        try {
            if (!startTime) startTime = timestamp;
            var progress = Math.min((timestamp - startTime) / ANIM_DURATION, 1);
            var ease = 1 - Math.pow(1 - progress, 3);

            // 重置变换，直接用实际像素坐标操作
            ctx.setTransform(1, 0, 0, 1, 0, 0);

            switch (pageTurnMode) {
                case 'cover':
                    drawCoverAnim(tmpOld, tmpNew, CW, CH, ease, isForward);
                    break;
                case 'slide':
                    drawSlideAnim(tmpOld, tmpNew, CW, CH, ease, isForward);
                    break;
                case 'updown':
                    drawUpDownAnim(tmpOld, tmpNew, CW, CH, ease, isForward);
                    break;
                case 'fade':
                    drawFadeAnim(tmpOld, tmpNew, CW, CH, ease);
                    break;
                default:
                    drawCoverAnim(tmpOld, tmpNew, CW, CH, ease, isForward);
            }

            if (progress < 1) {
                requestAnimationFrame(frame);
            } else {
                // 动画结束，恢复 DPR 变换并正常绘制
                ctx.setTransform(1, 0, 0, 1, 0, 0);
                ctx.scale(dpr, dpr);
                currentPage = toPage;
                drawPage(currentPage);
                notifyJavaPageChange();
                isAnimating = false;
                if (animWatchdog) { clearTimeout(animWatchdog); animWatchdog = null; }
            }
        } catch (e) {
            // 动画出错时安全恢复状态，防止 isAnimating 卡死
            console.error('翻页动画异常: ' + e.message);
            ctx.setTransform(1, 0, 0, 1, 0, 0);
            ctx.scale(dpr, dpr);
            currentPage = toPage;
            drawPage(currentPage);
            notifyJavaPageChange();
            isAnimating = false;
            if (animWatchdog) { clearTimeout(animWatchdog); animWatchdog = null; }
        }
    }
    requestAnimationFrame(frame);
}

// 覆盖动画：新页从侧边滑入覆盖旧页
function drawCoverAnim(oldCvs, newCvs, CW, CH, p, forward) {
    ctx.drawImage(oldCvs, 0, 0);
    var x = forward ? (CW * (1 - p)) : (-CW * (1 - p));
    ctx.drawImage(newCvs, x, 0);
    // 边缘阴影
    ctx.fillStyle = 'rgba(0,0,0,' + (0.25 * (1 - p)) + ')';
    ctx.fillRect(x, 0, 8, CH);
}

// 平移动画：旧页滑出 + 新页滑入
function drawSlideAnim(oldCvs, newCvs, CW, CH, p, forward) {
    ctx.clearRect(0, 0, CW, CH);
    var oldOff = forward ? (-CW * p) : (CW * p);
    var newOff = forward ? (CW * (1 - p)) : (-CW * (1 - p));
    ctx.drawImage(oldCvs, oldOff, 0);
    ctx.drawImage(newCvs, newOff, 0);
}

// 上下动画：垂直滑动
function drawUpDownAnim(oldCvs, newCvs, CW, CH, p, forward) {
    ctx.clearRect(0, 0, CW, CH);
    var oldOff = forward ? (-CH * p) : (CH * p);
    var newOff = forward ? (CH * (1 - p)) : (-CH * (1 - p));
    ctx.drawImage(oldCvs, 0, oldOff);
    ctx.drawImage(newCvs, 0, newOff);
}

// 淡入动画：交叉淡入淡出
function drawFadeAnim(oldCvs, newCvs, CW, CH, p) {
    ctx.clearRect(0, 0, CW, CH);
    ctx.globalAlpha = 1 - p;
    ctx.drawImage(oldCvs, 0, 0);
    ctx.globalAlpha = p;
    ctx.drawImage(newCvs, 0, 0);
    ctx.globalAlpha = 1;
}

// 仿真翻书动画：带3D透视的翻页效果
function drawSimulationAnim(oldCvs, newCvs, CW, CH, p, forward) {
    // 翻页中点时透视变形最强
    var perspective = Math.sin(p * Math.PI) * 0.055;
    var vShrink = CH * perspective;  // 上下各收缩的像素
    var hShift  = CW * perspective * 0.35; // 远端水平偏移

    if (forward) {
        var slideX = CW * (1 - p); // 新页左边缘位置

        // 1. 底层背景
        ctx.fillStyle = bgColor;
        ctx.fillRect(0, 0, CW, CH);

        // 2. 旧页（静止不动）
        ctx.drawImage(oldCvs, 0, 0);

        // 3. 旧页上随翻页移动的阴影
        var shL = Math.max(0, slideX - CW * 0.1);
        var shR = Math.min(CW, slideX + CW * 0.03);
        if (shR > shL) {
            var sg = ctx.createLinearGradient(shL, 0, shR, 0);
            sg.addColorStop(0, 'rgba(0,0,0,0)');
            sg.addColorStop(1, 'rgba(0,0,0,0.25)');
            ctx.fillStyle = sg;
            ctx.fillRect(shL, 0, shR - shL, CH);
        }

        // 4. 新页（带3D透视变形：上下收窄 + 梯形倾斜）
        ctx.save();
        ctx.beginPath();
        // 梯形：左侧（铰链边）全高，右侧（自由边）收缩
        ctx.moveTo(slideX, vShrink * 0.3);
        ctx.lineTo(slideX + CW, vShrink);
        ctx.lineTo(slideX + CW, CH - vShrink);
        ctx.lineTo(slideX, CH - vShrink * 0.3);
        ctx.closePath();
        ctx.clip();
        // 将新页绘制到梯形区域内
        ctx.drawImage(newCvs, slideX, 0);
        ctx.restore();

        // 5. 新页左边缘高光（纸张边缘反光）
        var hlL = Math.max(0, slideX);
        var hlR = Math.min(CW, slideX + 5);
        if (hlR > hlL && slideX > 0 && slideX < CW) {
            var hl = ctx.createLinearGradient(hlL, 0, hlR, 0);
            hl.addColorStop(0, 'rgba(255,255,255,0.4)');
            hl.addColorStop(1, 'rgba(255,255,255,0)');
            ctx.fillStyle = hl;
            ctx.fillRect(hlL, 0, hlR - hlL, CH);
        }

        // 6. 新页表面微弱暗角（靠近铰链处）
        var cL = Math.max(0, slideX);
        var cR = Math.min(CW, slideX + CW * 0.05);
        if (cR > cL && slideX > 0 && slideX < CW) {
            var cg = ctx.createLinearGradient(cL, 0, cR, 0);
            cg.addColorStop(0, 'rgba(0,0,0,0.05)');
            cg.addColorStop(1, 'rgba(0,0,0,0)');
            ctx.fillStyle = cg;
            ctx.fillRect(cL, 0, cR - cL, CH);
        }

    } else {
        // ===== 向右翻（镜像处理）=====
        var slideX2 = -CW * (1 - p); // 新页左边缘位置（负值→右移）

        ctx.fillStyle = bgColor;
        ctx.fillRect(0, 0, CW, CH);

        ctx.drawImage(oldCvs, 0, 0);

        // 旧页上阴影
        var shL2 = Math.max(0, slideX2 + CW - CW * 0.03);
        var shR2 = Math.min(CW, slideX2 + CW + CW * 0.1);
        if (shR2 > shL2) {
            var sg2 = ctx.createLinearGradient(shL2, 0, shR2, 0);
            sg2.addColorStop(0, 'rgba(0,0,0,0.25)');
            sg2.addColorStop(1, 'rgba(0,0,0,0)');
            ctx.fillStyle = sg2;
            ctx.fillRect(shL2, 0, shR2 - shL2, CH);
        }

        // 新页（带透视：右侧铰链边全高，左侧自由边收缩）
        var nLeft = slideX2;
        var nRight = slideX2 + CW;
        ctx.save();
        ctx.beginPath();
        ctx.moveTo(nLeft, vShrink);
        ctx.lineTo(nRight, vShrink * 0.3);
        ctx.lineTo(nRight, CH - vShrink * 0.3);
        ctx.lineTo(nLeft, CH - vShrink);
        ctx.closePath();
        ctx.clip();
        ctx.drawImage(newCvs, nLeft, 0);
        ctx.restore();

        // 新页右边缘高光
        var hlL2 = Math.max(0, nRight - 5);
        var hlR2 = Math.min(CW, nRight);
        if (hlR2 > hlL2 && nRight > 0 && nRight < CW) {
            var hl2 = ctx.createLinearGradient(hlL2, 0, hlR2, 0);
            hl2.addColorStop(0, 'rgba(255,255,255,0)');
            hl2.addColorStop(1, 'rgba(255,255,255,0.4)');
            ctx.fillStyle = hl2;
            ctx.fillRect(hlL2, 0, hlR2 - hlL2, CH);
        }

        // 新页暗角
        var cL2 = Math.max(0, nRight - CW * 0.05);
        var cR2 = Math.min(CW, nRight);
        if (cR2 > cL2 && nRight > 0 && nRight < CW) {
            var cg2 = ctx.createLinearGradient(cL2, 0, cR2, 0);
            cg2.addColorStop(0, 'rgba(0,0,0,0)');
            cg2.addColorStop(1, 'rgba(0,0,0,0.05)');
            ctx.fillStyle = cg2;
            ctx.fillRect(cL2, 0, cR2 - cL2, CH);
        }
    }
}

// ============ turn.js 仿真翻页 ============
var _reinitTimer = null;
var _simulationPlaceholderActive = false;
var _chapterEndTimer = null;  // 章节结束页停留后进入下一章的定时器

// ✅ 在章节内容到达前，先展示一个"空白书本"外观，替代默认的米黄 canvas 空白
// 效果：进入阅读器时，立即看到一个与最终书本风格一致的空页，而不是突变
function showSimulationPlaceholder() {
    if (_simulationPlaceholderActive) return;
    _simulationPlaceholderActive = true;

    // 1. 隐藏底层 canvas（仿真模式下始终由 flipbook 容器承担显示）
    canvas.style.visibility = 'hidden';

    // 2. 显示翻页容器，应用当前背景色/纹理（样式表规则统一同步，含延迟创建的背面页）
    var $container = $('#flipbookContainer');
    $container.css('display', 'block');
    syncSimulationBackground();
    // ✅ 纹理未加载完成时，直接用 URL 让浏览器并行加载（视觉上更快）
    if (!(bgTextureImg && bgTextureImg.complete) && _pendingTexture) {
        $container.css('background-image', 'url(' + _pendingTexture + ')');
        $container.css('background-size', '100% 100%');
    }

    // 3. 添加一个占位页（带正确背景色/纹理，不使用 turn.js 动画）
    var $flipbook = $('#flipbook');
    $flipbook.empty();
    var placeholder = $('<div class="page-div"></div>');
    placeholder.css({
        'background-color': bgColor,
        'width': '100%',
        'height': '100%'
    });
    if (bgTextureImg && bgTextureImg.complete) {
        placeholder.css('background-image', 'url(' + bgTextureImg.src + ')');
        placeholder.css('background-size', '100% 100%');
    } else if (_pendingTexture) {
        placeholder.css('background-image', 'url(' + _pendingTexture + ')');
        placeholder.css('background-size', '100% 100%');
    }
    $flipbook.append(placeholder);

    // 4. 不调用 turn()，保持最简单的 DOM 结构，等内容到达后 initSimulation 会清理并重建
}

// ✅ 清理占位模式（initSimulation 会重建真正的 turn.js 翻页）
function clearSimulationPlaceholder() {
    if (!_simulationPlaceholderActive) return;
    _simulationPlaceholderActive = false;
    var $flipbook = $('#flipbook');
    $flipbook.empty();
}

// ============ 仿真翻页：书页背面与底层页管理 ============
// turn.js single 模式下：
//   - 被翻起页面的背面（.page.p-temporal）→ 空白纸张背面
//   - 下一页内容 → 应在底层静止区域显示
// 翻页时当前页的 pageObj 被移到翻页层，pageWrapper 变空，
// 但 syncSimulationBackground 给 .page-wrapper 设置了底色（!important），
// 导致底层被遮挡。因此在翻页开始时把目标页渲染图设为 .page-wrapper 背景
// （!important 覆盖底色），当前页 pageObj 移走后即露出下一页内容；
// 翻页结束后清除，恢复正常纹理背景。
var _simBackRAF = null;

// 清空 p-temporal 上可能残留的内容（背景图、img 子节点），
// 让书页背面保持纯空白（由 syncSimulationBackground 统一的底色/纹理）
function simClearBack() {
    var styleEl = document.getElementById('simBackStyle');
    if (styleEl) styleEl.textContent = '';
    $('#flipbook .page.p-temporal').each(function () {
        var old = this.querySelector('img');
        if (old) old.remove();
    });
}

// 翻页开始：
//   1. 把目标页渲染图设为 .page-wrapper 背景，当前页 pageObj 移走后即露出下一页
//   2. 清空书页背面残留内容，显示 p-temporal
// 注意：不在翻页开始时调用 ensureSimulationPages，因为 addPage 内部会调用 turn("stop")，
// 会中断正在开始的翻页动画。相邻页在初始化和翻页完成后预加载即可。
function simOnFlipStart(targetPageNum) {
    if (_simBackRAF) { cancelAnimationFrame(_simBackRAF); _simBackRAF = null; }
    simClearBack();
    // 确保 p-temporal 可见（turn.js 翻页动画需要它作为背面）
    var pt = document.querySelector('#flipbook .page.p-temporal');
    if (pt) pt.style.display = '';

    // 把目标页渲染图设为 .page-wrapper 背景（!important 覆盖 syncSimulationBackground 的底色）
    var targetIdx = targetPageNum - 1;
    if (targetIdx >= 0 && targetIdx < pages.length) {
        if (simPageCache[targetIdx] === undefined) renderPageToCache(targetIdx);
        var url = simPageCache[targetIdx];
        var styleEl = document.getElementById('simUnderPageStyle');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'simUnderPageStyle';
            document.head.appendChild(styleEl);
        }
        styleEl.textContent = '#flipbook .page-wrapper {' +
            ' background-image: url(' + url + ') !important;' +
            ' background-size: 100% 100% !important;' +
            ' background-repeat: no-repeat !important;' +
            ' background-position: 0 0 !important;' +
            ' }';
    }
}

// 翻页结束：
//   1. 清除底层页背景样式，恢复正常纹理
//   2. 清理书页背面，隐藏 p-temporal
function simOnFlipEnd() {
    if (_simBackRAF) { cancelAnimationFrame(_simBackRAF); _simBackRAF = null; }
    simClearBack();
    // 清除底层页背景样式
    var underStyleEl = document.getElementById('simUnderPageStyle');
    if (underStyleEl) underStyleEl.textContent = '';
    var pt = document.querySelector('#flipbook .page.p-temporal');
    if (pt) pt.style.display = 'none';
    // 恢复纹理兜底
    syncSimulationBackground();
}

function initSimulation() {
    if (pages.length === 0) return;

    // 清理可能存在的占位页，并将 simulationActive 统一在初始化后设置
    clearSimulationPlaceholder();

    // 如果已处于仿真激活状态，先销毁（destroySimulation 会重置 simulationActive=false）
    if (simulationActive) {
        destroySimulation();
    }
    simulationActive = true;
    simPageCache = {};

    if (!renderCanvas) {
        renderCanvas = document.createElement('canvas');
        renderCtx = renderCanvas.getContext('2d');
    }
    renderCanvas.width = canvas.width;
    renderCanvas.height = canvas.height;

    var flipbook = $('#flipbook');
    flipbook.empty();

    var W = window.innerWidth;
    var H = window.innerHeight;

    // ✅ 关键修复：预渲染当前页（currentPage）为图片后再启动 turn.js
    // 避免 turn.js 初始化期间出现空页
    var firstPageIdx = currentPage;
    if (firstPageIdx < 0 || firstPageIdx >= pages.length) firstPageIdx = 0;

    // 先填充当前页（带正确背景色）再启动 turn.js；
    // createSimPageElement 会带上 p{n} 类名，保证从章节中间恢复时页码不错位
    flipbook.append(createSimPageElement(firstPageIdx));

    // 确保容器背景与内容一致（含纹理：正反书页背景统一由 syncSimulationBackground 同步）
    $('#flipbookContainer').css('display', 'block');
    syncSimulationBackground();

    // 隐藏底层 canvas（仿真模式下始终由 flipbook 容器承担显示）
    canvas.style.visibility = 'hidden';

    flipbook.turn({
        width: W,
        height: H,
        display: 'single',
        duration: 500,  // 翻页动画时长，500ms 比默认 600ms 更流畅
        gradients: true,
        acceleration: true,
        elevation: 60,
        page: firstPageIdx + 1,  // 当前页，从 1 开始
        pages: pages.length + 1,  // 末页之后追加"章节结束页"，让最后一页的翻页动画完整
        when: {
            start: function(event, opts) {
                // 翻页开始：确保目标页已加入 DOM（底层静止区域才能显示下一页），
                // 同时清空书页背面残留内容，让背面保持空白纸张效果
                if (opts && opts.next) simOnFlipStart(opts.next);
            },
            turning: function(event, page) {
                currentPage = page - 1;
                // 结束页不向 Java 上报页码（马上会进入下一章）
                if (page <= pages.length) notifyJavaPageChange();
                // 从结束页翻回时取消章节切换定时器
                if (_chapterEndTimer && page < pages.length + 1) {
                    clearTimeout(_chapterEndTimer);
                    _chapterEndTimer = null;
                }
            },
            turned: function(event, page) {
                currentPage = page - 1;
                // 仿真模式下 canvas 已隐藏，无需 drawPage（避免无效渲染消耗性能）
                // 翻页结束：清理书页背面，隐藏 p-temporal
                simOnFlipEnd();
                // 翻页结束后预加载新位置附近的页面（前两页~后三页），
                // 保证下一次拖拽翻页时露出的页面已有内容
                ensureSimulationPages(currentPage);
                // 翻到"章节结束页"：翻页动画完成后延迟进入下一章
                if (page >= pages.length + 1) {
                    if (_chapterEndTimer) clearTimeout(_chapterEndTimer);
                    _chapterEndTimer = setTimeout(function() {
                        _chapterEndTimer = null;
                        try { Android.onChapterEnd(); } catch(e) {}
                    }, 600);
                }
            },
            missing: function(event, missingPages) {
                for (var i = 0; i < missingPages.length; i++) {
                    var pg = missingPages[i];
                    var idx = pg - 1;
                    if (idx >= 0 && idx <= pages.length) {
                        // 页面不存在时渲染并注册（松手触发 missing 的兜底路径）
                        flipbook.turn('addPage', createSimPageElement(idx), pg);
                    }
                }
                // 直接同步背景，避免 setTimeout 延迟导致的闪烁
                syncSimulationBackground();
            }
        }
    });

    // ✅ 预加载当前页附近的页面为页对象：
    // turn.js 拖拽折页阶段不会触发 missing，必须提前加入 DOM 才不会露空白
    ensureSimulationPages(firstPageIdx);

    // 初始化时隐藏 p-temporal（书页背面），避免其背景色遮挡当前页正面内容。
    // p-temporal 仅在翻页动画期间由 simOnFlipStart 显示，翻页结束由 simOnFlipEnd 隐藏。
    var initPt = document.querySelector('#flipbook .page.p-temporal');
    if (initPt) initPt.style.display = 'none';
}

function destroySimulation() {
    // 同时处理"占位模式"与"仿真激活"两种状态的清理
    if (!simulationActive && !_simulationPlaceholderActive) return;
    simulationActive = false;
    // 取消章节切换定时器，避免销毁后仍触发进入下一章
    if (_chapterEndTimer) { clearTimeout(_chapterEndTimer); _chapterEndTimer = null; }
    clearSimulationPlaceholder();
    try {
        var flipbook = $('#flipbook');
        if (flipbook.data() && flipbook.turn('is')) {
            flipbook.turn('destroy');
        }
    } catch (e) {
        console.error('销毁 flipbook 异常: ' + e);
    }
    $('#flipbookContainer').css({ display: 'none' });
    // ✅ 恢复 canvas 显示（切换模式或退出仿真模式时）
    canvas.style.visibility = 'visible';
    simPageCache = {};
}

function renderPageToCache(pageIndex) {
    var dpr = window.devicePixelRatio || 1;

    // Render to off-screen canvas to avoid flickering the main display
    renderCtx.setTransform(1, 0, 0, 1, 0, 0);
    renderCtx.scale(dpr, dpr);

    var savedPage = currentPage;
    currentPage = pageIndex;
    var realCtx = ctx;
    ctx = renderCtx;
    drawPage(pageIndex);
    ctx = realCtx;
    currentPage = savedPage;

    var dataUrl = renderCanvas.toDataURL('image/jpeg', 0.85);
    simPageCache[pageIndex] = dataUrl;
    return dataUrl;
}

// ✅ 创建仿真页元素：内容为整页渲染图，类名 p{n} 让 turn.js
// 按类名注册页码（addPage 中类名优先于显式页码参数，避免恢复到章中间时错位）
// 说明：内容必须用 <img> 子节点承载（而非仅 CSS background-image），
// 因为 turn.js 折页动画会把页元素的 DOM 子节点复制进折页层；
// 仅靠 background-image 会让折页层在动画期间是空白，导致翻页时下一页不显示。
// 添加 fixed 类：turn.js 的 _necessPage 会保留有 fixed 类的页在 DOM 中，
// 防止 _removeFromDOM 在 range 变化时把预加载的相邻页移除，
// 确保翻页时底层静止区域能正确显示下一页内容。
function createSimPageElement(pageIndex) {
    // 章节结束页（pageIndex == pages.length）：不渲染 canvas，
    // 直接构造带背景色/纹理与"本章完"提示的页面
    if (pageIndex >= pages.length) {
        var endEl = $('<div class="page-div p' + (pageIndex + 1) + ' page fixed"></div>');
        endEl.css({ 'background-color': bgColor });
        if (bgTextureImg && bgTextureImg.complete) {
            endEl.css('background-image', 'url(' + bgTextureImg.src + ')');
            endEl.css('background-size', '100% 100%');
        } else if (_pendingTexture) {
            endEl.css('background-image', 'url(' + _pendingTexture + ')');
            endEl.css('background-size', '100% 100%');
        }
        var endText = document.createElement('div');
        endText.textContent = '本章完';
        endText.style.cssText = 'position:absolute;top:50%;left:0;width:100%;text-align:center;' +
            'transform:translateY(-50%);font-size:' + (fontSize + 6) + 'px;color:' + textColor + ';';
        endEl[0].appendChild(endText);
        return endEl;
    }
    if (simPageCache[pageIndex] === undefined) {
        renderPageToCache(pageIndex);
    }
    var element = $('<div class="page-div p' + (pageIndex + 1) + ' page fixed"></div>');
    element.css({
        // 保留底色，作为折页层内的兜底；正页内容由 img 子节点呈现
        'background-color': bgColor
    });
    // 用 <img> 承载整页渲染图：位置铺满，保证折页时也能被 turn.js 复制
    var img = document.createElement('img');
    img.src = simPageCache[pageIndex];
    img.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;object-fit:fill;';
    img.className = 'page-content';
    element[0].appendChild(img);
    return element;
}

/**
 * ✅ 预加载当前页附近的页面（前两页 ~ 后三页）为 turn.js 页对象。
 * 原因：turn.js 只在"松手"(_turnPage)时才触发 missing 事件补页，
 * 拖拽折页的整个过程中下一页根本不在 DOM 里，导致翻页时露出的下一页为空。
 * 必须提前把相邻页作为页对象加入 flipbook，拖拽时才能立即看到内容。
 */
function ensureSimulationPages(centerIdx) {
    if (!simulationActive || !pages.length) return;
    var flipbook = $('#flipbook');
    try {
        var data = flipbook.data();
        if (!data || !data.pageObjs || !flipbook.turn('is')) return;
        var added = false;
        // 预加载范围：前两页 ~ 后三页（共6页，含章节结束页），减少翻页时即时渲染
        for (var off = -2; off <= 3; off++) {
            var idx = centerIdx + off;
            if (idx < 0 || idx > pages.length) continue; // 结束页 index 为 pages.length
            var pg = idx + 1;
            if (data.pageObjs[pg]) continue; // 已存在，无需重复添加
            flipbook.turn('addPage', createSimPageElement(idx), pg);
            added = true;
        }
        // 只有实际添加了新页时才同步背景，避免不必要的重绘
        if (added) syncSimulationBackground();
    } catch (e) {
        console.error('ensureSimulationPages 异常: ' + e);
    }
}

function reinitSimulation() {
    if (!simulationActive) return;
    // Debounce: wait for all pending settings calls before rebuilding
    if (_reinitTimer) clearTimeout(_reinitTimer);
    _reinitTimer = setTimeout(function() {
        _reinitTimer = null;
        destroySimulation();
        simulationActive = false; // ensure clean state
        initSimulation();
    }, 80);
}

// ============ 触摸翻页 ============
var touchStartX = 0;
canvas.addEventListener('touchstart', function(e) {
    if (simulationActive) return;
    touchStartX = e.touches[0].clientX;
});

canvas.addEventListener('touchend', function(e) {
    if (isAnimating || simulationActive) return;
    var deltaX = e.changedTouches[0].clientX - touchStartX;
    if (deltaX < -50) {
        nextPage();
    } else if (deltaX > 50) {
        prevPage();
    }
});

// ============ Java 调用接口 ============
function loadContent(chapterIndex, title, content, startPage, isLocal, progress) {
    currentChapterIndex = chapterIndex;
    chapterTitle = title;
    currentContent = preprocessContent(content);

    // 接收本地书标志和阅读进度
    if (typeof isLocal !== 'undefined') {
        isLocalBook = isLocal;
    }
    if (typeof progress !== 'undefined') {
        bookProgress = progress;
    }

    console.log('loadContent 接收内容长度: ' + content.length);
    resizeCanvas();

    if (startPage === -1 && pages.length > 0) {
        currentPage = pages.length - 1;
    } else if (typeof startPage === 'number' && startPage > 0 && startPage <= pages.length) {
        currentPage = startPage - 1;
    } else {
        currentPage = 0;
    }
    console.log('reader.html 开始绘制第 ' + (currentPage + 1) + ' 页');

    // ✅ 仿真模式：跳过 canvas 直接渲染为翻页页（canvas 已隐藏）
    // 非仿真模式：正常绘制到 canvas
    if (pageTurnMode === 'simulation') {
        initSimulation();
    } else {
        drawPage(currentPage);
        // 如果之前处于仿真占位模式，现在切换到非仿真需清理
        if (_simulationPlaceholderActive || simulationActive) {
            destroySimulation();
        }
    }

    // ✅ 如果还有 pending 的字体/纹理未完成加载（startPendingResources 还在异步中）
    // 加载完成后会自动调用 paginate+drawPage（由 startPendingResources 处理）

    notifyJavaPageChange();
    var summary = {
        chapterIndex: chapterIndex,
        totalPages: pages.length,
        fontSize: fontSize,
        width: window.innerWidth,
        height: window.innerHeight,
        pageStarts: pageStartOffsets
    };
    try { Android.onPaginationComplete(JSON.stringify(summary)); } catch (e) {}
}

// ============ 内容预处理 ============
function preprocessContent(text) {
    if (!text) return '';
    // 将 <br>, <br/>, <p>, </p>, <div>, </div> 等转为换行
    text = text.replace(/<br\s*\/?>/gi, '\n');
    text = text.replace(/<\/p>/gi, '\n');
    text = text.replace(/<p[^>]*>/gi, '');
    text = text.replace(/<\/div>/gi, '\n');
    text = text.replace(/<div[^>]*>/gi, '');
    // 去除其余 HTML 标签
    text = text.replace(/<[^>]+>/g, '');
    // 解码常见 HTML 实体
    text = text.replace(/&nbsp;/g, ' ');
    text = text.replace(/&lt;/g, '<');
    text = text.replace(/&gt;/g, '>');
    text = text.replace(/&amp;/g, '&');
    text = text.replace(/&quot;/g, '"');
    text = text.replace(/&#39;/g, "'");
    // 统一换行符
    text = text.replace(/\r\n/g, '\n');
    text = text.replace(/\r/g, '\n');
    // 合并连续空行（超过2个空行压缩为2个）
    text = text.replace(/\n{3,}/g, '\n\n');
    return text;
}

function setFontSize(size) {
    fontSize = size;
    titleFontSize = size + 4;
    topPadding = padding + fontSize; // 字号变化时同步顶部边距
    if (currentContent) {
        paginate(currentContent, chapterTitle);
        if (!_deferDraw) {
            drawPage(currentPage);
            notifyJavaPageChange();
            if (simulationActive) reinitSimulation();
        }
    }
}

function setFontFamily(name) {
    if (name === '__system__') {
        // 系统字体：使用系统默认字体栈
        fontFamily = 'sans-serif';
    } else {
        fontFamily = name;
    }
    if (currentContent) {
        paginate(currentContent, chapterTitle);
        if (!_deferDraw) {
            drawPage(currentPage);
            notifyJavaPageChange();
            if (simulationActive) reinitSimulation();
        }
    }
}

function loadCustomFont(src, name) {
    // 如果字体已经在 document.fonts 中，直接切换并重绘（避免重复异步加载）
    try {
        var iter = document.fonts.values();
        var entry = iter.next();
        while (!entry.done) {
            if (entry.value.family === '"' + name + '"' || entry.value.family === name) {
                fontFamily = name;
                if (currentContent) {
                    paginate(currentContent, chapterTitle);
                    if (!_deferDraw) {
                        drawPage(currentPage);
                        notifyJavaPageChange();
                        if (simulationActive) reinitSimulation();
                    }
                }
                return;
            }
            entry = iter.next();
        }
    } catch (e) {}

    var wasDeferred = _deferDraw;
    var font = new FontFace(name, 'url(' + src + ')');
    font.load().then(function(loaded) {
        document.fonts.add(loaded);
        fontFamily = name;
        if (currentContent) {
            paginate(currentContent, chapterTitle);
            drawPage(currentPage);
            notifyJavaPageChange();
            if (simulationActive) reinitSimulation();
        }
        // If batch was active, finish it now that font is loaded
        if (wasDeferred) {
            _deferDraw = false;
        }
    }).catch(function(err) {
        console.error('加载自定义字体失败: ' + err);
        if (wasDeferred) finishSettingsBatch();
    });
}

function setBackgroundColor(color) {
    bgColor = color;
    // ✅ 在仿真模式下，通过样式表规则统一更新容器和 wrapper 背景（含纹理同步，折角不镂空）
    if (_simulationPlaceholderActive || simulationActive) {
        $('#flipbook .page-div').css('background-color', color);
        syncSimulationBackground();
    }
    if (!_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

function setTextColor(color) {
    textColor = color;
    for (var i = 0; i < pages.length; i++) {
        for (var j = 0; j < pages[i].length; j++) {
            pages[i][j].color = color;
        }
    }
    if (!_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

function setBackgroundTexture(src) {
    // 如果 bgTextureImg 已经加载了同一张图，直接重绘（避免重复异步加载）
    if (bgTextureImg && bgTextureImg.complete && bgTextureImg.src && bgTextureImg.src.indexOf(src) !== -1) {
        if (currentContent && !_deferDraw) {
            drawPage(currentPage);
            if (simulationActive) reinitSimulation();
        }
        return;
    }

    var img = new Image();
    img.onload = function() {
        bgTextureImg = img;
        // ✅ 仿真占位模式：加载完成后立即更新占位页背景
        if (_simulationPlaceholderActive) {
            $('#flipbook .page-div').css('background-image', 'url(' + img.src + ')');
            $('#flipbook .page-div').css('background-size', '100% 100%');
        }
        // ✅ 仿真模式（占位/激活）：统一同步容器与翻页背面纹理
        if (_simulationPlaceholderActive || simulationActive) {
            syncSimulationBackground();
        }
        if (currentContent) {
            if (!_deferDraw) {
                drawPage(currentPage);
                if (simulationActive) reinitSimulation();
            }
        }
    };
    img.onerror = function() {
        console.error('加载纹理背景失败: ' + src);
    };
    img.src = src;
}

function clearBackgroundTexture() {
    bgTextureImg = null;
    // ✅ 仿真模式（占位/激活）：清除纹理只留纯色（样式表规则统一同步）
    if (_simulationPlaceholderActive) {
        $('#flipbook .page-div').css('background-image', 'none');
    }
    if (_simulationPlaceholderActive || simulationActive) {
        syncSimulationBackground();
    }
    if (currentContent && !_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

function setNightMode(enable) {
    isNightMode = enable;
    bgTextureImg = null;
    if (enable) {
        bgColor = '#1A1A1A';
        textColor = '#AAAAAA';
    } else {
        bgColor = '#F5F5DC';
        textColor = '#333333';
    }
    // ✅ 夜间模式切换时立即更新仿真容器和 wrapper 背景（纹理已清除，样式表统一同步）
    if (_simulationPlaceholderActive || simulationActive) {
        $('#flipbook .page-div').css({
            'background-color': bgColor,
            'background-image': 'none'
        });
        syncSimulationBackground();
    }
    for (var i = 0; i < pages.length; i++) {
        for (var j = 0; j < pages[i].length; j++) {
            pages[i][j].color = textColor;
        }
    }
    if (!_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

// 设置页眉页脚显示
function setShowHeaderFooter(show) {
    showHeaderFooter = show;
    if (!_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

// 设置页眉页脚字体大小
function setHeaderFooterFontSize(size) {
    headerFooterFontSize = size;
    if (!_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

// 设置电量时间显示开关
function setShowBatteryTime(show) {
    showBatteryTime = show;
    if (!_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

// 更新电量时间数据
function updateBatteryTime(level, time) {
    batteryText = level;
    timeText = time;
    if (showBatteryTime && !_deferDraw) {
        drawPage(currentPage);
        if (simulationActive) reinitSimulation();
    }
}

function beginSettingsBatch() {
    _deferDraw = true;
}

function finishSettingsBatch() {
    _deferDraw = false;
    if (currentContent) {
        drawPage(currentPage);
        notifyJavaPageChange();
        if (simulationActive) reinitSimulation();
    }
}

function jumpToPage(pageNumber) {
    goToPage(pageNumber - 1);
}

// ============ 后台预计算 ============
function preCalculate(content) {
    var oldContent = currentContent;
    var oldTitle = chapterTitle;
    var oldFontSize = fontSize;
    var oldFontFamily = fontFamily;
    var processed = preprocessContent(content);
    currentContent = processed;
    chapterTitle = '';
    paginate(processed, '');
    var summary = {
        totalPages: pages.length,
        fontSize: fontSize,
        width: window.innerWidth,
        height: window.innerHeight,
        pageStarts: pageStartOffsets
    };
    currentContent = oldContent;
    chapterTitle = oldTitle;
    fontSize = oldFontSize;
    fontFamily = oldFontFamily;
    return JSON.stringify(summary);
}

// ============ 初始化 ============
resizeCanvas();
