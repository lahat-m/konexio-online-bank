/*
 * Screen 0.6, turned into the keypad in the mock.
 *
 * The page arrives with an ordinary PIN field and a hidden keypad, so it works
 * before this runs and if it never runs. What this does is swap which of the two
 * the customer sees, and keep the dots in step with what the field holds — the
 * field is still the thing that submits.
 */
(function () {
    'use strict';

    var pad = document.querySelector('.pin');
    if (!pad) {
        return;
    }

    var input = pad.querySelector('.pin__input');
    var dots = Array.prototype.slice.call(pad.querySelectorAll('.pin__dot'));
    var keys = Array.prototype.slice.call(pad.querySelectorAll('.keypad__key'));
    var length = parseInt(pad.getAttribute('data-pin-length'), 10) || dots.length;

    if (!input || keys.length === 0) {
        return;
    }

    // From here the keypad is the way in, so show it and take the field away.
    pad.classList.add('pin--keypad');

    function draw() {
        var entered = input.value.length;
        dots.forEach(function (dot, index) {
            dot.classList.toggle('pin__dot--filled', index < entered);
        });
    }

    function append(digit) {
        if (input.value.length < length) {
            input.value += digit;
            draw();
        }
    }

    function remove() {
        input.value = input.value.slice(0, -1);
        draw();
    }

    keys.forEach(function (key) {
        key.addEventListener('click', function () {
            if (key.getAttribute('data-action') === 'delete') {
                remove();
            } else {
                append(key.getAttribute('data-digit'));
            }
        });
    });

    // A hardware keyboard should still work: somebody on a laptop, and anybody
    // using a keyboard because a touchscreen is not how they use a phone.
    document.addEventListener('keydown', function (event) {
        if (event.key >= '0' && event.key <= '9') {
            append(event.key);
        } else if (event.key === 'Backspace') {
            event.preventDefault();
            remove();
        }
    });

    draw();
})();
