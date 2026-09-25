/*
 * The Share button on screen 4.2.
 *
 * Two different buttons depending on the browser: the system share sheet where
 * there is one, and copying the receipt's link where there is not. Hidden until
 * this runs and picks, because a Share button that does nothing is worse than a
 * Download button on its own — which is what is left without it.
 */
(function () {
    'use strict';

    var button = document.querySelector('.share');
    if (!button) {
        return;
    }

    var label = button.querySelector('.share__label');
    var url = window.location.href;
    var title = document.title;

    if (navigator.share) {
        button.hidden = false;
        button.addEventListener('click', function () {
            // A cancelled share rejects, and a customer who changed their mind
            // has not hit an error.
            navigator.share({ title: title, url: url }).catch(function () {});
        });
        return;
    }

    if (navigator.clipboard && label) {
        button.hidden = false;
        var revert;
        label.textContent = 'Copy link';
        button.addEventListener('click', function () {
            navigator.clipboard.writeText(url).then(function () {
                label.textContent = 'Copied';
                window.clearTimeout(revert);
                revert = window.setTimeout(function () {
                    label.textContent = 'Copy link';
                }, 2000);
            });
        });
        return;
    }

    // Neither: leave it hidden. Download PDF still works, and the URL is in the
    // address bar for anyone who wants it.
})();
