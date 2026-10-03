/* =====================================================================================
   XRay AntiCheat - admin panel behaviour
   =====================================================================================
   Progressive enhancement only. Every action in this panel is a real HTML form that works
   with JavaScript disabled; this file adds confirmation, feedback and motion on top. That
   ordering matters: a moderation console whose "ban" button silently does nothing when a
   script fails to load is a console that lies about what it did.

   No inline handlers and no eval anywhere - the Content-Security-Policy for this panel
   allows scripts from 'self' only, with no 'unsafe-inline', so anything of the sort would
   be blocked (and is not wanted regardless).
   ===================================================================================== */

(function () {
  'use strict';

  /* ---------------------------------------------------------------- toast */

  var toastTimer = null;

  function toast(message) {
    var element = document.getElementById('toast');
    if (!element) {
      return;
    }
    element.textContent = message;
    element.classList.add('is-visible');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () {
      element.classList.remove('is-visible');
    }, 2200);
  }

  /* ---------------------------------------------------------------- confirmations
     Destructive actions ask first. The check runs on submit rather than on click so that
     pressing Enter in a text field is covered too. */

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) {
      return;
    }

    var submitter = event.submitter;
    var question = null;

    if (submitter && submitter.hasAttribute('data-confirm')) {
      question = submitter.getAttribute('data-confirm');
    } else {
      var field = form.querySelector('[data-confirm]');
      if (field) {
        question = field.getAttribute('data-confirm');
      }
    }

    if (question && !window.confirm(question)) {
      event.preventDefault();
      return;
    }

    // Guard against a double submission: two bans from one impatient double-click would be
    // recorded twice in the audit trail, which is misleading even though it is harmless.
    if (submitter instanceof HTMLButtonElement) {
      submitter.disabled = true;
      submitter.classList.add('is-disabled');
      window.setTimeout(function () {
        submitter.disabled = false;
        submitter.classList.remove('is-disabled');
      }, 4000);
    }
  });

  /* ---------------------------------------------------------------- copy to clipboard */

  document.addEventListener('click', function (event) {
    var button = event.target.closest ? event.target.closest('[data-copy]') : null;
    if (!button) {
      return;
    }
    var value = button.getAttribute('data-copy');
    if (!value) {
      return;
    }

    function done() {
      toast('Copied to clipboard');
    }

    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(value).then(done, function () {
        toast('Could not access the clipboard');
      });
      return;
    }

    // Fallback for a browser that withholds the async clipboard API. Loopback is a secure
    // context, so this path is rarely taken, but a copy button that does nothing is worse
    // than a slightly old-fashioned one that works.
    var scratch = document.createElement('textarea');
    scratch.value = value;
    scratch.setAttribute('readonly', 'readonly');
    scratch.style.position = 'fixed';
    scratch.style.opacity = '0';
    document.body.appendChild(scratch);
    scratch.select();
    try {
      document.execCommand('copy');
      done();
    } catch (err) {
      toast('Could not access the clipboard');
    }
    document.body.removeChild(scratch);
  });

  /* ---------------------------------------------------------------- animated counters */

  function animateValue(element) {
    var text = element.textContent.trim();
    // Only animate plain integers. A percentage, a dash or a word is left alone: inventing a
    // tween for "never" or "12.4%" would be noise dressed as polish.
    if (!/^\d+$/.test(text)) {
      return;
    }
    var target = parseInt(text, 10);
    if (!isFinite(target) || target === 0 || target > 1000000) {
      return;
    }

    var prefersReduced = window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (prefersReduced) {
      return;
    }

    var duration = 520;
    var start = null;

    function step(timestamp) {
      if (start === null) {
        start = timestamp;
      }
      var progress = Math.min(1, (timestamp - start) / duration);
      // Ease-out: fast at first, settling on the value.
      var eased = 1 - Math.pow(1 - progress, 3);
      element.textContent = Math.round(target * eased).toLocaleString();
      if (progress < 1) {
        window.requestAnimationFrame(step);
      } else {
        element.textContent = target.toLocaleString();
      }
    }

    element.textContent = '0';
    window.requestAnimationFrame(step);
  }

  function animateAll(root) {
    var scope = root || document;
    var values = scope.querySelectorAll('.stat__value');
    for (var i = 0; i < values.length; i++) {
      animateValue(values[i]);
    }
  }

  /* ---------------------------------------------------------------- live stats
     The dashboard re-reads /api/stats on an interval so an operator watching a server does
     not have to reload to see the candidate count move. Failure is silent and stops the
     polling: if the endpoint is not answering, the page is still perfectly usable, and a
     stream of error toasts would be worse than stale numbers. */

  function pollStats() {
    var targets = document.querySelectorAll('[data-stat]');
    if (!targets.length || !window.fetch) {
      return;
    }

    var stopped = false;
    var failures = 0;

    function tick() {
      if (stopped) {
        return;
      }
      fetch('/api/stats', {
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json' }
      }).then(function (response) {
        if (!response.ok) {
          throw new Error('status ' + response.status);
        }
        return response.json();
      }).then(function (data) {
        failures = 0;
        for (var i = 0; i < targets.length; i++) {
          var key = targets[i].getAttribute('data-stat');
          if (Object.prototype.hasOwnProperty.call(data, key)) {
            var next = String(data[key]);
            if (targets[i].textContent.trim() !== next) {
              targets[i].textContent = next;
              // A brief flash draws the eye to the one number that changed, rather than
              // making the whole row twitch.
              targets[i].animate(
                [{ opacity: 0.35 }, { opacity: 1 }],
                { duration: 420, easing: 'ease-out' }
              );
            }
          }
        }
      }).catch(function () {
        failures++;
        if (failures >= 3) {
          stopped = true;
        }
      });
    }

    window.setInterval(tick, 15000);
  }

  /* ---------------------------------------------------------------- boot */

  function boot() {
    animateAll(document);
    pollStats();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
