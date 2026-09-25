/*
 * The eye on the balance card.
 *
 * Hidden until this runs, like the Copy button beside it: a toggle that cannot
 * toggle is worse than no toggle. What it hides is only what is on the screen —
 * the balance is still in the page, because the point is the person standing
 * behind you, not the person reading the HTML.
 */
(function () {
    'use strict';

    var toggle = document.querySelector('.balance-toggle');
    var amount = document.querySelector('.balance-amount');
    if (!toggle || !amount) {
        return;
    }

    toggle.classList.add('balance-toggle--ready');

    var shown = amount.textContent;
    var hidden = '••••••';
    var visible = true;

    toggle.addEventListener('click', function () {
        visible = !visible;
        amount.textContent = visible ? shown : hidden;
        toggle.setAttribute('aria-label', visible ? 'Hide balance' : 'Show balance');
    });
})();
