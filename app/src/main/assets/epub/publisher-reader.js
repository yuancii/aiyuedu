/* App-owned DOM positioning only. Publication scripts never execute. */
(function () {
  if (window.legadoPublisher) return;
  function nodes() {
    var result = [], walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var node = walker.currentNode, parent = node.parentElement;
      if (!parent || parent.closest('script,style,noscript') || !node.data.trim()) continue;
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
      .then(function () { requestAnimationFrame(function () { apply(); window.legadoPublisher.restored = true; }); });
  }
  window.legadoPublisher = { capture: capture, restore: restore };
})();
