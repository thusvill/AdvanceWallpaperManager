(() => {
  const REPO = 'thusvill/AdvanceWallpaperManager';
  const API = `https://api.github.com/repos/${REPO}`;
  const heroDownload = document.getElementById('heroDownload');

  const escapeHtml = (value = '') => String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');

  const safeUrl = (value = '') => {
    try {
      const url = new URL(value, window.location.href);
      return /^https?:$/.test(url.protocol) ? url.href : '#';
    } catch {
      return '#';
    }
  };

  document.addEventListener('error', (event) => {
    const image = event.target;
    if (image instanceof HTMLImageElement && image.classList.contains('icon') && !image.dataset.fallback) {
      image.dataset.fallback = '1';
      image.style.display = 'none';
    }
  }, true);

  const formatNumber = (value) => new Intl.NumberFormat().format(value ?? 0);
  const formatDate = (value) => value ? new Date(value).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';

  const renderInline = (text) => {
    let html = escapeHtml(text);
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => `<a href="${safeUrl(url)}" target="_blank" rel="noreferrer">${label}</a>`);
    return html;
  };

  const renderMarkdown = (markdown = '') => {
    const lines = markdown.replace(/\r/g, '').split('\n');
    const out = [];
    let listOpen = false;
    let codeOpen = false;

    const closeList = () => {
      if (listOpen) { out.push('</ul>'); listOpen = false; }
    };

    for (const raw of lines) {
      const line = raw.trimEnd();
      if (line.startsWith('```')) {
        closeList();
        if (!codeOpen) { out.push('<pre><code>'); codeOpen = true; }
        else { out.push('</code></pre>'); codeOpen = false; }
        continue;
      }
      if (codeOpen) {
        out.push(`${escapeHtml(line)}\n`);
        continue;
      }
      if (!line.trim()) { closeList(); continue; }
      if (/^#{1,3} /.test(line)) {
        closeList();
        const level = line.match(/^#+/)[0].length;
        out.push(`<h${Math.min(level + 2, 5)}>${renderInline(line.replace(/^#{1,3} /, ''))}</h${Math.min(level + 2, 5)}>`);
      } else if (/^[-*] /.test(line)) {
        if (!listOpen) { out.push('<ul>'); listOpen = true; }
        out.push(`<li>${renderInline(line.replace(/^[-*] /, ''))}</li>`);
      } else {
        closeList();
        out.push(`<p>${renderInline(line)}</p>`);
      }
    }
    if (codeOpen) out.push('</code></pre>');
    closeList();
    return out.join('');
  };

  const themeKey = 'awm-theme';
  const themeToggle = document.getElementById('themeToggle');
  const themeIcon = document.getElementById('themeIcon');
  const applyTheme = (theme) => {
    document.documentElement.dataset.theme = theme;
    const dark = theme === 'dark';
    themeIcon.src = dark ? 'https://img.icons8.com/ios-glyphs/24/000000/sun--v1.png' : 'https://img.icons8.com/ios-glyphs/24/000000/moon-symbol.png';
    themeToggle?.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
    const metaTheme = document.querySelector('meta[name="theme-color"]');
    if (metaTheme) metaTheme.setAttribute('content', dark ? '#10141c' : '#f3f6ff');
  };

  const storedTheme = localStorage.getItem(themeKey);
  const systemDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  applyTheme(storedTheme || (systemDark ? 'dark' : 'light'));
  themeToggle?.addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem(themeKey, next);
    applyTheme(next);
  });

  const depthDescription = document.getElementById('depthDescription');
  const depthCopy = {
    base: 'The image sits at the bottom of the stack. Position, scale and rotate it before the depth mask is applied.',
    clock: 'The clock is composed as its own layer, with your selected layout, size, spacing, color and font.',
    subject: 'The foreground subject is rendered after the clock, so the subject visibly occludes the clock for the depth effect.'
  };

  document.querySelectorAll('[data-depth]').forEach((button) => {
    button.addEventListener('click', () => {
      document.querySelectorAll('[data-depth]').forEach((node) => node.classList.remove('active'));
      button.classList.add('active');
      depthDescription.textContent = depthCopy[button.dataset.depth];
    });
  });

  const editorContent = document.getElementById('editorContent');
  const editorViews = {
    wallpaper: () => `
      <div class="control-stack">
        <div class="control-row"><label>Scale</label><span class="control-value">1.00×</span><div class="fake-slider" style="--fill:68%"><i></i></div></div>
        <div class="control-row"><label>Rotation</label><span class="control-value">0°</span><div class="fake-slider" style="--fill:24%"><i></i></div></div>
        <div class="control-row"><label>Quick snap</label><span class="control-value">0°</span><div class="chip-row"><span class="fake-chip selected">0°</span><span class="fake-chip">90°</span><span class="fake-chip">180°</span><span class="fake-chip">270°</span></div></div>
      </div>`,
    model: () => `
      <div class="control-stack">
        <div class="control-row"><label>Segmentation</label><span class="control-value">ML Kit</span><div class="chip-row"><span class="fake-chip selected">ML Kit</span></div></div>
        <div class="control-row"><label>Threshold</label><span class="control-value">0.55</span><div class="fake-slider" style="--fill:56%"><i></i></div></div>
        <div class="control-row"><label>Expansion</label><span class="control-value">+2 px</span><div class="fake-slider" style="--fill:46%"><i></i></div></div>
        <div class="control-row"><label>Feather</label><span class="control-value">8 px</span><div class="fake-slider" style="--fill:62%"><i></i></div></div>
        <div class="control-row"><label>Clock depth</label><span class="control-value">0.85</span><div class="fake-slider" style="--fill:84%"><i></i></div></div>
      </div>`,
    clock: () => `
      <div class="control-stack">
        <div class="control-row"><label>Layout</label><span class="control-value">Horizontal</span><div class="chip-row"><span class="fake-chip selected">Horizontal</span><span class="fake-chip">Vertical</span></div></div>
        <div class="control-row"><label>Font</label><span class="control-value">Custom</span><div class="chip-row"><span class="fake-chip selected">Custom font</span><span class="fake-chip">System</span></div></div>
        <div class="control-row"><label>Time</label><span class="control-value">24h</span><div class="chip-row"><span class="fake-chip selected">24h</span><span class="fake-chip">12h</span></div></div>
        <div class="control-row"><label>Size</label><span class="control-value">64</span><div class="fake-slider" style="--fill:72%"><i></i></div></div>
      </div>`
  };

  const setEditorTab = (name) => {
    document.querySelectorAll('.editor-tab').forEach((tab) => {
      const active = tab.dataset.tab === name;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', String(active));
    });
    editorContent.innerHTML = editorViews[name]();
  };

  document.querySelectorAll('.editor-tab').forEach((tab) => tab.addEventListener('click', () => setEditorTab(tab.dataset.tab)));
  setEditorTab('wallpaper');

  async function getJson(url) {
    const response = await fetch(url, { headers: { Accept: 'application/vnd.github+json' } });
    if (!response.ok) throw new Error(`GitHub request failed: ${response.status}`);
    return response.json();
  }

  async function loadGitHub() {
    const releaseList = document.getElementById('releaseList');
    const contributors = document.getElementById('contributors');
    const releaseStatus = document.getElementById('releaseStatus');
    const contributorStatus = document.getElementById('contributorStatus');

    try {
      const repo = await getJson(API);
      document.getElementById('repoStars').textContent = formatNumber(repo.stargazers_count);
      document.getElementById('repoForks').textContent = formatNumber(repo.forks_count);
      document.getElementById('repoIssues').textContent = formatNumber(repo.open_issues_count);
      document.getElementById('repoLicense').textContent = repo.license?.spdx_id || 'GNU';
      document.getElementById('heroStarCount').textContent = formatNumber(repo.stargazers_count);
    } catch {
      document.getElementById('repoStars').textContent = '—';
      document.getElementById('heroStarCount').textContent = '—';
      document.getElementById('repoForks').textContent = '—';
      document.getElementById('repoIssues').textContent = '—';
    }

    try {
      const releases = await getJson(`${API}/releases?per_page=6`);
      releaseStatus.textContent = releases.length ? `${releases.length} shown` : 'No releases';
      if (!releases.length) {
        releaseList.innerHTML = '<div class="empty-state">No published releases yet.</div>';
      } else {
        const latestWithApk = releases.find((release) => (release.assets || []).some((asset) => /\.apk$/i.test(asset.name)));
        if (heroDownload && latestWithApk) {
          const apk = latestWithApk.assets.find((asset) => /\.apk$/i.test(asset.name));
          heroDownload.href = safeUrl(apk.browser_download_url);
          heroDownload.target = '_blank';
          heroDownload.rel = 'noreferrer';
        }
        releaseList.innerHTML = releases.map((release, index) => {
          const assets = (release.assets || []).filter((asset) => /\.(apk|aab|zip)$/i.test(asset.name));
          const assetButtons = assets.length
            ? assets.map((asset) => `<a class="asset-button shape-pill" href="${escapeHtml(safeUrl(asset.browser_download_url))}" target="_blank" rel="noreferrer"><img class="icon" src="https://img.icons8.com/material-sharp/16/000000/download.png" alt="" aria-hidden="true">${escapeHtml(asset.name)}</a>`).join('')
            : `<a class="asset-button shape-pill" href="${escapeHtml(safeUrl(release.html_url))}" target="_blank" rel="noreferrer"><img class="icon" src="https://img.icons8.com/material-sharp/16/000000/external-link.png" alt="" aria-hidden="true">Open release</a>`;

          return `<article class="release-card">
            <div class="release-main">
              <div class="release-top">
                <strong>${escapeHtml(release.name || release.tag_name)}</strong>
                ${index === 0 ? '<span class="release-badge">Latest</span>' : ''}
                <span class="release-date">${escapeHtml(formatDate(release.published_at || release.created_at))}</span>
              </div>
              <div class="release-notes">${renderMarkdown(release.body || 'No release notes provided.')}</div>
            </div>
            <div class="release-footer">
              <div class="asset-list">${assetButtons}</div>
              <a class="release-link" href="${escapeHtml(safeUrl(release.html_url))}" target="_blank" rel="noreferrer"><img class="icon" src="https://img.icons8.com/material-sharp/16/000000/external-link.png" alt="" aria-hidden="true">Release</a>
            </div>
          </article>`;
        }).join('');
      }
    } catch {
      releaseStatus.textContent = 'Unavailable';
      releaseList.innerHTML = '<div class="empty-state">GitHub releases could not be loaded right now.</div>';
    }

    try {
      const repo = await getJson(API);
      const list = await getJson(`${API}/contributors?per_page=12&anon=false`);
      const ownerLogin = repo.owner?.login || REPO.split('/')[0];
      const owner = {
        login: ownerLogin,
        html_url: repo.owner?.html_url || `https://github.com/${ownerLogin}`,
        avatar_url: repo.owner?.avatar_url || `https://github.com/${ownerLogin}.png?size=96`,
        contributions: 'Owner'
      };
      const merged = [owner, ...list.filter((person) => person.login && person.login !== owner.login).slice(0, 11)];
      contributorStatus.textContent = `${merged.length} shown`;
      contributors.innerHTML = merged.map((person) => `<a class="contributor" href="${escapeHtml(safeUrl(person.html_url))}" target="_blank" rel="noreferrer">
        <img src="${escapeHtml(safeUrl(person.avatar_url))}" alt="${escapeHtml(person.login)} avatar" loading="lazy">
        <span><strong>${escapeHtml(person.login)}</strong><span class="${person.contributions === 'Owner' ? 'owner-badge' : ''}">${escapeHtml(String(person.contributions))}${person.contributions === 'Owner' ? '' : ' contributions'}</span></span>
      </a>`).join('');
    } catch {
      contributorStatus.textContent = 'Owner shown';
      const owner = REPO.split('/')[0];
      contributors.innerHTML = `<a class="contributor" href="https://github.com/${owner}" target="_blank" rel="noreferrer">
        <img src="https://github.com/${owner}.png?size=96" alt="${owner} avatar">
        <span><strong>${owner}</strong><span class="owner-badge">Owner</span></span>
      </a>`;
    }
  }

  loadGitHub();
})();
