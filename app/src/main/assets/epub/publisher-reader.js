/* App-owned reading, selection and annotations. Publication scripts never execute. */
(function () {
  if (window.legadoPublisher) return;
  function nodes() {
    var result = [], walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var node = walker.currentNode, parent = node.parentElement;
      if (!parent || parent.closest('script,style,noscript,[data-legado-ui]') || !node.data.trim()) continue;
      var style = getComputedStyle(parent);
      if (style.display === 'none' || style.visibility === 'hidden') continue;
      result.push(node);
    }
    return result;
  }
  function path(node) {
    var result = [];
    while (node && node !== document.body) {
      var parent = node.parentNode;
      if (!parent) return [];
      result.unshift(Array.prototype.indexOf.call(parent.childNodes, node));
      node = parent;
    }
    return result;
  }
  function atPath(parts) {
    var node = document.body;
    for (var i = 0; node && i < parts.length; i++) node = node.childNodes[parts[i]];
    return node && node.nodeType === 3 ? node : null;
  }
  function scrollToText(node, offset) {
    var range = document.createRange();
    offset = Math.max(0, Math.min(offset, Math.max(0, node.length - 1)));
    range.setStart(node, offset); range.setEnd(node, Math.min(node.length, offset + 1));
    var rect = range.getBoundingClientRect();
    window.scrollBy(rect.left < 0 ? rect.left : 0, rect.top - 12);
  }
  function capture() {
    var list = nodes(), target = null, offset = 0, before = 0;
    var caret = document.caretRangeFromPoint && document.caretRangeFromPoint(innerWidth / 3, 20);
    if (caret && list.indexOf(caret.startContainer) >= 0) {
      target = caret.startContainer; offset = caret.startOffset;
    }
    if (!target) {
      for (var i = 0; i < list.length; i++) {
        var range = document.createRange(); range.selectNodeContents(list[i]);
        var rect = range.getBoundingClientRect();
        if (rect.bottom > 0 && rect.top < innerHeight) { target = list[i]; break; }
      }
    }
    var total = list.reduce(function (n, node) { return n + node.length; }, 0);
    for (var j = 0; j < list.length && list[j] !== target; j++) before += list[j].length;
    var all = list.map(function (node) { return node.data; }).join('');
    var position = target ? before + offset : 0;
    var result = { path: target ? path(target) : [], offset: offset,
      quote: all.substring(position, position + 64),
      fraction: total ? position / total : 0,
      scroll: scrollY / Math.max(1, document.documentElement.scrollHeight - innerHeight) };
    var endCaret = document.caretRangeFromPoint && document.caretRangeFromPoint(innerWidth / 3, innerHeight - 16);
    if (endCaret && list.indexOf(endCaret.startContainer) >= 0) {
      var endPosition = endCaret.startOffset;
      for (var k = 0; k < list.length && list[k] !== endCaret.startContainer; k++) endPosition += list[k].length;
      result.endQuote = all.substring(endPosition, endPosition + 64);
      result.endFraction = endPosition / Math.max(1, total);
    }
    var anchors = window.legadoPublisherAnchors || [];
    var nearestTop = -Infinity;
    anchors.forEach(function (anchor) {
      var element = document.getElementById(anchor.id);
      var top = element && element.getBoundingClientRect().top;
      if (element && top <= 40 && top > nearestTop) { nearestTop = top; result.chapter = anchor.index; }
    });
    return result;
  }
  function restore(locator, fragment) {
    function apply() {
      if (locator && locator.quote) {
        var node = atPath(locator.path || []);
        if (node && node.data.substring(locator.offset || 0).indexOf(locator.quote.substring(0, 16)) === 0) {
          scrollToText(node, locator.offset || 0); return;
        }
        var list = nodes(), text = list.map(function (n) { return n.data; }).join('');
        var compact = '', offsets = [];
        for (var i = 0; i < text.length; i++) if (!/\s/.test(text[i])) { compact += text[i]; offsets.push(i); }
        var needle = locator.quote.replace(/\s/g, ''), match = compact.indexOf(needle), best = -1;
        var expected = (locator.fraction || 0) * compact.length;
        while (match >= 0) {
          if (best < 0 || Math.abs(match - expected) < Math.abs(best - expected)) best = match;
          match = compact.indexOf(needle, match + 1);
        }
        if (best >= 0) {
          var position = offsets[best];
          for (var k = 0; k < list.length; k++) {
            if (position < list[k].length) { scrollToText(list[k], position); return; }
            position -= list[k].length;
          }
        }
      }
      if (fragment) {
        var element = document.getElementById(fragment);
        if (element) { element.scrollIntoView(); return; }
      }
      var fallback = locator && (typeof locator.scroll === 'number' ? locator.scroll : locator.fraction) || 0;
      window.scrollTo(0, Math.max(0, Math.min(1, fallback)) *
        Math.max(0, document.documentElement.scrollHeight - innerHeight));
    }
    var ready = document.fonts ? document.fonts.ready : Promise.resolve();
    window.legadoPublisher.restored = false;
    var settled = Promise.all([ready].concat(Array.from(document.images).map(function (image) {
      if (image.complete) return Promise.resolve();
      return new Promise(function (resolve) { image.addEventListener('load', resolve, {once:true});
        image.addEventListener('error', resolve, {once:true}); });
    })));
    Promise.race([settled, new Promise(function (resolve) { setTimeout(resolve, 3000); })])
      .then(function () { requestAnimationFrame(function () {
        apply(); window.legadoPublisher.restored = true; redraw();
      }); });
  }
  function textIndex() {
    var list = nodes(), text = '', starts = [];
    list.forEach(function (node) { starts.push(text.length); text += node.data; });
    return { nodes: list, starts: starts, text: text };
  }
  function selection() {
    var selected = getSelection();
    if (!selected || selected.isCollapsed || !selected.rangeCount) return null;
    var range = selected.getRangeAt(0), index = textIndex(), start = -1, end = -1;
    index.nodes.forEach(function (node, i) {
      if (!range.intersectsNode(node)) return;
      var from = node === range.startContainer ? range.startOffset : 0;
      var to = node === range.endContainer ? range.endOffset : node.length;
      if (to <= from) return;
      if (start < 0) start = index.starts[i] + from;
      end = index.starts[i] + to;
    });
    if (start < 0 || end <= start) return null;
    var rect = range.getBoundingClientRect();
    var result = { quote: index.text.substring(start, end), start: start, end: end,
      fraction: start / Math.max(1, index.text.length),
      prefix: index.text.substring(Math.max(0, start - 32), start),
      suffix: index.text.substring(end, end + 32), x: rect.left, y: rect.top, bottom: rect.bottom };
    var nearest = -Infinity;
    (window.legadoPublisherAnchors || []).forEach(function (anchor) {
      var element = document.getElementById(anchor.id), top = element && element.getBoundingClientRect().top;
      if (element && top <= rect.top + 1 && top > nearest) { nearest = top; result.chapter = anchor.index; }
    });
    return result;
  }
  function findRange(index, annotation) {
    var compact = '', offsets = [];
    for (var i = 0; i < index.text.length; i++) if (!/\s/.test(index.text[i])) {
      compact += index.text[i]; offsets.push(i);
    }
    var needle = (annotation.quote || '').replace(/\s/g, '');
    if (!needle) return null;
    var expected = (annotation.fraction || 0) * compact.length, best = -1, score = Infinity;
    var prefix = (annotation.prefix || '').replace(/\s/g, '');
    var suffix = (annotation.suffix || '').replace(/\s/g, '');
    for (var match = compact.indexOf(needle); match >= 0; match = compact.indexOf(needle, match + 1)) {
      var distance = Math.abs(match - expected);
      if (prefix && compact.substring(Math.max(0, match - prefix.length), match) !== prefix) distance += compact.length;
      if (suffix && compact.substring(match + needle.length, match + needle.length + suffix.length) !== suffix) distance += compact.length;
      if (distance < score) { best = match; score = distance; }
    }
    if (best < 0) return null;
    var from = offsets[best], to = offsets[best + needle.length - 1] + 1, range = document.createRange();
    for (var j = 0; j < index.nodes.length; j++) {
      var length = index.nodes[j].length, base = index.starts[j];
      if (from >= base && from < base + length) range.setStart(index.nodes[j], from - base);
      if (to > base && to <= base + length) { range.setEnd(index.nodes[j], to - base); break; }
    }
    return range;
  }
  var annotations = [], ranges = [], overlay, lastEvent = null;
  function paintInk() {
    document.querySelectorAll('[data-legado-ink]').forEach(function (span) {
      var parent = span.parentNode;
      while (span.firstChild) parent.insertBefore(span.firstChild, span);
      span.remove(); parent.normalize();
    });
    var fonts = [];
    annotations.forEach(function (annotation, number) {
      var paint = annotation.paint || {};
      if (!paint.bold && !paint.italic && !paint.fontSize && paint.letterSpacing == null &&
          !paint.fontUrl && !paint.shadow && !paint.emphasis && !paint.textColor) return;
      var index = textIndex(), range = findRange(index, annotation);
      if (!range) return;
      var parts = [];
      index.nodes.forEach(function (node) {
        if (!range.intersectsNode(node)) return;
        var start = node === range.startContainer ? range.startOffset : 0;
        var end = node === range.endContainer ? range.endOffset : node.length;
        if (end > start) parts.push({node: node, start: start, end: end});
      });
      parts.reverse().forEach(function (part) {
        var node = part.node;
        if (part.end < node.length) node.splitText(part.end);
        if (part.start) node = node.splitText(part.start);
        var span = document.createElement('span'); span.setAttribute('data-legado-ink', '');
        if (paint.bold) span.style.setProperty('font-weight', 'bold', 'important');
        if (paint.italic) span.style.setProperty('font-style', 'italic', 'important');
        if (paint.fontSize) span.style.setProperty('font-size', paint.fontSize + 'px', 'important');
        if (paint.letterSpacing != null) span.style.setProperty('letter-spacing', paint.letterSpacing + 'em', 'important');
        if (paint.textColor) span.style.setProperty('color', paint.textColor, 'important');
        if (paint.shadow) span.style.setProperty('text-shadow', paint.shadow, 'important');
        if (paint.emphasis) { span.style.setProperty('text-emphasis', 'dot ' + paint.emphasis, 'important'); }
        if (paint.fontUrl) {
          span.style.setProperty('font-family', 'legado-note-' + number, 'important');
          fonts.push('@font-face{font-family:legado-note-' + number + ';src:url(' + JSON.stringify(paint.fontUrl) + ')}');
        }
        node.parentNode.insertBefore(span, node); span.appendChild(node);
      });
    });
    var sheet = document.getElementById('legado-annotation-fonts');
    if (!sheet) { sheet = document.createElement('style'); sheet.id = 'legado-annotation-fonts'; document.head.appendChild(sheet); }
    var fontRules = fonts.join('\n');
    if (sheet.textContent !== fontRules) sheet.textContent = fontRules;
  }
  function redraw() {
    if (!getSelection().isCollapsed) return;
    paintInk();
    if (!overlay) {
      overlay = document.createElement('div'); overlay.setAttribute('data-legado-ui', 'annotations');
      overlay.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483000';
      document.documentElement.appendChild(overlay);
    }
    overlay.replaceChildren(); ranges = [];
    var index = textIndex();
    annotations.forEach(function (annotation) {
      var range = findRange(index, annotation);
      if (!range) return;
      ranges.push({ range: range, annotation: annotation });
      Array.from(range.getClientRects()).forEach(function (rect) {
        if (!rect.width || !rect.height) return;
        var mark = document.createElement('div');
        mark.style.cssText = 'position:absolute;box-sizing:border-box;pointer-events:none;mix-blend-mode:multiply;';
        mark.style.left = (rect.left + scrollX) + 'px'; mark.style.top = (rect.top + scrollY) + 'px';
        mark.style.width = rect.width + 'px'; mark.style.height = rect.height + 'px';
        var style = annotation.paint || {};
        if (style.fill && !(window.CSS && CSS.highlights && window.Highlight)) {
          mark.style.background = style.fill; mark.style.opacity = '0.4'; mark.style.mixBlendMode = 'normal';
        }
        if (style.rounded) mark.style.borderRadius = '3px';
        if (style.underline) mark.style.borderBottom = style.underline;
        if (style.box) mark.style.border = '1px solid ' + style.box;
        if (style.strike) mark.style.backgroundImage = 'linear-gradient(transparent 47%, ' + style.strike + ' 47%, ' + style.strike + ' 53%, transparent 53%)';
        overlay.appendChild(mark);
      });
    });
    // CSS highlights paint glyphs without wrapping publisher nodes or invalidating DOM locators.
    if (window.CSS && CSS.highlights && window.Highlight) {
      CSS.highlights.forEach(function (_, key) { if (key.indexOf('legado-') === 0) CSS.highlights.delete(key); });
      var rules = [];
      ranges.forEach(function (item, i) {
        var paint = item.annotation.paint || {}, name = 'legado-' + i;
        CSS.highlights.set(name, new Highlight(item.range));
        var css = '';
        if (paint.textColor) css += 'color:' + paint.textColor + ';';
        if (paint.fill) css += 'background-color:' + paint.fill + ';';
        if (css) rules.push('::highlight(' + name + '){' + css + '}');
      });
      var sheet = document.getElementById('legado-highlight-paint');
      if (!sheet) { sheet = document.createElement('style'); sheet.id = 'legado-highlight-paint'; document.head.appendChild(sheet); }
      var paintRules = rules.join('\n');
      if (sheet.textContent !== paintRules) sheet.textContent = paintRules;
    }
  }
  function setAnnotations(items) { annotations = items || []; redraw(); }
  document.addEventListener('click', function (event) {
    if (!getSelection().isCollapsed || event.target.closest('a,button,input,select,textarea')) return;
    for (var i = ranges.length - 1; i >= 0; i--) {
      var rects = ranges[i].range.getClientRects();
      for (var j = 0; j < rects.length; j++) {
        var r = rects[j];
        if (event.clientX >= r.left && event.clientX <= r.right && event.clientY >= r.top && event.clientY <= r.bottom) {
          lastEvent = {type: 'annotation', id: ranges[i].annotation.id, x: event.clientX, y: event.clientY}; return;
        }
      }
    }
    lastEvent = {type: 'menu', x: event.clientX, y: event.clientY};
  }, true);
  window.addEventListener('resize', function () { requestAnimationFrame(redraw); });
  document.addEventListener('load', function (event) {
    if (event.target.tagName === 'IMG') requestAnimationFrame(redraw);
  }, true);
  if (document.fonts) document.fonts.addEventListener('loadingdone', function () { requestAnimationFrame(redraw); });
  var changedStyles = new Map();
  function setStyle(element, property, value) {
    if (!changedStyles.has(element)) changedStyles.set(element, {});
    var saved = changedStyles.get(element);
    if (!(property in saved)) saved[property] = [element.style.getPropertyValue(property), element.style.getPropertyPriority(property)];
    element.style.setProperty(property, value, 'important');
  }
  function applyStyle(config, keys) {
    var position = capture();
    changedStyles.forEach(function (saved, element) {
      Object.keys(saved).forEach(function (property) {
        if (saved[property][0]) element.style.setProperty(property, saved[property][0], saved[property][1]);
        else element.style.removeProperty(property);
      });
    });
    changedStyles.clear();
    var parents = new Set(nodes().map(function (node) { return node.parentElement.closest('[data-legado-ink]') ?
      node.parentElement.closest('[data-legado-ink]').parentElement : node.parentElement; }));
    var sizes = new Map();
    parents.forEach(function (element) { sizes.set(element, parseFloat(getComputedStyle(element).fontSize) || 16); });
    parents.forEach(function (element) {
      var baseSize = sizes.get(element);
      if (keys.indexOf('textSize') >= 0) setStyle(element, 'font-size', baseSize * config.textSize / 20 + 'px');
      if (keys.indexOf('letterSpacing') >= 0) setStyle(element, 'letter-spacing', config.letterSpacing + 'em');
      if (keys.indexOf('textBold') >= 0) setStyle(element, 'font-weight', config.textBold === 1 ? 'bold' : config.textBold === 2 ? '300' : 'normal');
      if (keys.indexOf('textFont') >= 0) setStyle(element, 'font-family', config.fontUrl ? 'legado-reader-font' : 'sans-serif');
      if (keys.indexOf('lineSpacingExtra') >= 0) setStyle(element, 'line-height', String(1.2 + config.lineSpacingExtra / 20));
      if (keys.indexOf('textColor') >= 0) setStyle(element, 'color', config.textColor);
    });
    if (keys.indexOf('background') >= 0) {
      setStyle(document.documentElement, 'background', 'transparent');
      setStyle(document.body, 'background', 'transparent');
      parents.forEach(function (element) { setStyle(element, 'background-color', 'transparent'); });
    }
    if (keys.indexOf('padding') >= 0) {
      setStyle(document.body, 'box-sizing', 'border-box'); setStyle(document.body, 'margin', '0');
      setStyle(document.body, 'padding', config.padding.map(function (n) { return n + 'px'; }).join(' '));
    }
    document.querySelectorAll('p,div').forEach(function (element) {
      if (element.closest('[data-legado-ui]')) return;
        if (keys.indexOf('textFullJustify') >= 0) setStyle(element, 'text-align', config.textFullJustify ? 'justify' : 'left');
      if (keys.indexOf('paragraphSpacing') >= 0) setStyle(element, 'margin-bottom', config.paragraphSpacing / 10 + 'em');
      if (keys.indexOf('paragraphIndent') >= 0) setStyle(element, 'text-indent', config.paragraphIndent + 'em');
    });
    document.querySelectorAll('h1,h2,h3,h4,h5,h6').forEach(function (element) {
      var targets = [element].concat(Array.from(element.querySelectorAll('*')));
      targets.forEach(function (target) {
        if (keys.indexOf('titleSize') >= 0) setStyle(target, 'font-size', (config.textSize + config.titleSize) + 'px');
        if (keys.indexOf('titleBold') >= 0 && config.titleBold >= 0)
          setStyle(target, 'font-weight', config.titleBold === 1 ? 'bold' : config.titleBold === 2 ? '300' : 'normal');
        if (keys.indexOf('titleColor') >= 0) setStyle(target, 'color', config.titleColor);
        if (keys.indexOf('titleFont') >= 0) setStyle(target, 'font-family', config.titleFontUrl ? 'legado-title-font' : 'sans-serif');
        if (keys.indexOf('titleLineSpacingExtra') >= 0) setStyle(target, 'line-height', String(1.2 + config.titleLineSpacingExtra / 20));
      });
      if (keys.indexOf('titleSpacing') >= 0) {
        setStyle(element, 'margin-top', config.titleSpacing[0] + 'px');
        setStyle(element, 'margin-bottom', config.titleSpacing[1] + 'px');
      }
      if (keys.indexOf('titleMode') >= 0) {
        if (config.titleMode === 2) setStyle(element, 'display', 'none');
        else setStyle(element, 'text-align', ['left', 'center', 'left', 'right'][config.titleMode] || 'left');
      }
    });
    var font = document.getElementById('legado-reader-font');
    if (!font) { font = document.createElement('style'); font.id = 'legado-reader-font'; document.head.appendChild(font); }
    font.textContent = (config.fontUrl ? '@font-face{font-family:legado-reader-font;src:url(' + JSON.stringify(config.fontUrl) + ')}' : '') +
      (config.titleFontUrl ? '@font-face{font-family:legado-title-font;src:url(' + JSON.stringify(config.titleFontUrl) + ')}' : '');
    restore(position, ''); requestAnimationFrame(redraw);
  }
  function turn(direction, smooth) {
    var max = Math.max(0, document.documentElement.scrollHeight - innerHeight);
    if ((direction > 0 && scrollY >= max - 2) || (direction < 0 && scrollY <= 2)) return false;
    window.scrollBy({top: direction * innerHeight * 0.9, behavior: smooth ? 'smooth' : 'instant'}); return true;
  }
  window.legadoPublisher = { capture: capture, restore: restore, selection: selection,
    clearSelection: function () { getSelection().removeAllRanges(); redraw(); },
    annotations: setAnnotations, applyStyle: applyStyle, turn: turn,
    event: function () { var event = lastEvent; lastEvent = null; return event; } };
})();
