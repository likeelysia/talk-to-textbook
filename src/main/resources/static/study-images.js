/* 大图预览只复用本地 object URL，不上传，也不持久化图片。 */
(() => {
    const dialog = document.getElementById('imagePreviewDialog');
    const image = document.getElementById('imagePreviewFull');
    const name = document.getElementById('imagePreviewName');
    const close = document.getElementById('closeImagePreview');
    const zoom = document.getElementById('zoomImagePreview');
    let previousFocus;
    function reset() {
        image.removeAttribute('src'); image.alt = ''; name.textContent = '';
        dialog.classList.remove('original-size'); zoom.textContent = '查看原尺寸';
    }
    window.openStudyImage = (url, filename) => {
        if (!String(url).startsWith('blob:')) return;
        previousFocus = document.activeElement;
        reset(); image.src = url; image.alt = filename; name.textContent = filename;
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
        close.focus();
    };
    window.closeStudyImage = () => {
        if (dialog.open && typeof dialog.close === 'function') dialog.close();
        else { dialog.removeAttribute('open'); reset(); }
    };
    close.addEventListener('click', window.closeStudyImage);
    dialog.addEventListener('click', event => { if (event.target === dialog) window.closeStudyImage(); });
    dialog.addEventListener('close', () => { reset(); previousFocus?.focus(); });
    zoom.addEventListener('click', () => {
        const original = dialog.classList.toggle('original-size');
        zoom.textContent = original ? '适应屏幕' : '查看原尺寸';
    });
})();
