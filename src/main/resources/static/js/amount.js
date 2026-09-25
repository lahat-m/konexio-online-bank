/*
 * The one-tap amounts on screen 3.1b, and the grouping in the field beside them.
 *
 * Both are conveniences over a field that already works: the pills are hidden
 * until this runs, and the grouping is cosmetic — the server strips the commas
 * back out, so a customer who types 5000 and one who taps 5,000 send the same
 * number.
 */
(function () {
    'use strict';

    var field = document.querySelector('.amount-field__input');
    var pills = Array.prototype.slice.call(document.querySelectorAll('.pill'));
    var submit = document.querySelector('.screen__actions .button--primary');
    var available = field && field.getAttribute('data-available');
    if (!field) {
        return;
    }

    function group(value) {
        var digits = value.replace(/[^0-9.]/g, '');
        var parts = digits.split('.');
        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        // More than two decimals is not a shilling amount; the server says so too.
        return parts.length > 1 ? parts[0] + '.' + parts[1].slice(0, 2) : parts[0];
    }

    function mark() {
        var typed = field.value.replace(/[^0-9.]/g, '');
        pills.forEach(function (pill) {
            var amount = pill.getAttribute('data-amount');
            pill.classList.toggle('pill--chosen', typed !== '' && Number(amount) === Number(typed));
        });
    }

    /*
     * Live feedback on the one thing the customer can see for themselves: asking
     * for more than the balance. The server says so too — this only says it
     * sooner, and greys the button the mock greys.
     */
    function check() {
        if (!submit || available === null || available === undefined) {
            return;
        }
        var typed = Number(field.value.replace(/[^0-9.]/g, ''));
        var overdrawn = typed > Number(available);
        submit.disabled = overdrawn || !typed;
        field.closest('.field').classList.toggle('field--invalid', overdrawn);
    }

    field.addEventListener('input', function () {
        var caretAtEnd = field.selectionStart === field.value.length;
        field.value = group(field.value);
        if (caretAtEnd) {
            field.setSelectionRange(field.value.length, field.value.length);
        }
        mark();
        check();
    });

    pills.forEach(function (pill) {
        pill.classList.add('pill--ready');
        pill.addEventListener('click', function () {
            field.value = group(pill.getAttribute('data-amount'));
            mark();
            check();
            field.focus();
        });
    });

    field.value = group(field.value);
    mark();
    check();
})();
