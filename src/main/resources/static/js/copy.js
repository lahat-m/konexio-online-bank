/*
 * The Copy button on screen 0.8.
 *
 * Hidden until this runs, because a button that cannot copy is worse than no
 * button: the number beside it is selectable text, which is how anyone without
 * a working clipboard API gets it.
 */
(function () {
    'use strict';

    if (!navigator.clipboard) {
        return;
    }

    Array.prototype.forEach.call(document.querySelectorAll('.copy'), function (button) {
        button.classList.add('copy--ready');

        var label = button.textContent.trim();
        var revert;

        button.addEventListener('click', function () {
            navigator.clipboard.writeText(button.getAttribute('data-copy')).then(function () {
                // Say so in the button itself. A toast would be one more thing to
                // build, and this is read by whoever just tapped it.
                button.textContent = 'Copied';
                window.clearTimeout(revert);
                revert = window.setTimeout(function () {
                    button.textContent = label;
                }, 2000);
            });
        });
    });
})();
