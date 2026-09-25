/*
 * Screen 0.5, made comfortable. Everything here is a convenience over a form
 * that already works: six inputs that submit, and a resend the server re-enables
 * on the next request. If this file never loads, the screen still verifies a
 * code — it just asks the customer to tap between boxes and refresh to resend.
 */
(function () {
    'use strict';

    var boxes = Array.prototype.slice.call(document.querySelectorAll('.otp__digit'));
    var countdown = document.querySelector('.otp__countdown');
    var resend = document.querySelector('.otp__resend');

    if (boxes.length > 0) {
        boxes[0].focus();
        boxes.forEach(function (box, index) {
            // Typing moves on; a box that already has a digit is replaced rather
            // than appended to, which is what a second keystroke means here.
            box.addEventListener('input', function () {
                box.value = box.value.replace(/[^0-9]/g, '').slice(-1);
                if (box.value && index < boxes.length - 1) {
                    boxes[index + 1].focus();
                }
            });

            // Backspace in an empty box goes back a box, so holding it clears the
            // code the way it would clear one field.
            box.addEventListener('keydown', function (event) {
                if (event.key === 'Backspace' && !box.value && index > 0) {
                    boxes[index - 1].focus();
                } else if (event.key === 'ArrowLeft' && index > 0) {
                    boxes[index - 1].focus();
                } else if (event.key === 'ArrowRight' && index < boxes.length - 1) {
                    boxes[index + 1].focus();
                }
            });

            // A code arrives from a text message as six digits in one paste.
            box.addEventListener('paste', function (event) {
                var pasted = (event.clipboardData || window.clipboardData).getData('text');
                var digits = pasted.replace(/[^0-9]/g, '').split('');
                if (digits.length === 0) {
                    return;
                }
                event.preventDefault();
                boxes.forEach(function (target, position) {
                    if (position >= index && digits.length > 0) {
                        target.value = digits.shift();
                    }
                });
                boxes[Math.min(boxes.length - 1, index + pasted.length)].focus();
            });
        });
    }

    if (countdown && resend) {
        var remaining = parseInt(countdown.getAttribute('data-seconds'), 10) || 0;
        var tick = window.setInterval(function () {
            remaining -= 1;
            if (remaining > 0) {
                countdown.textContent = 'Resend code in ' + Math.floor(remaining / 60)
                    + ':' + String(remaining % 60).padStart(2, '0');
                return;
            }
            window.clearInterval(tick);
            countdown.remove();
            resend.classList.remove('is-hidden');
        }, 1000);
    }
})();
