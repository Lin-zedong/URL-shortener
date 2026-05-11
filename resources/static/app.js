(function () {
    "use strict";

    function getCurrentRelativePath() {
        return window.location.pathname + window.location.search;
    }

    function getDashboardI18nNode() {
        return document.getElementById("dashboard-i18n");
    }

    function getDashboardText(datasetKey, fallback) {
        const node = getDashboardI18nNode();
        if (!node || !node.dataset) {
            return fallback;
        }
        const value = node.dataset[datasetKey];
        return value && value.trim() ? value : fallback;
    }

    function isRussianInterface() {
        return document.documentElement.lang === "ru";
    }

    function onReady(callback) {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", callback, { once: true });
            return;
        }
        callback();
    }

    /*
     * JS-версия переключения языка оставлена ради совместимости со старыми страницами; в новой версии переключатель уже работает через обычные ссылки.
     */
    window.appSwitchLang = function appSwitchLang(langCode) {
        const target = new URL("/app/lang", window.location.origin);
        target.searchParams.set("value", langCode);
        target.searchParams.set("redirect", getCurrentRelativePath());
        window.location.href = target.toString();
    };

    window.appToast = function appToast(message) {
        let toast = document.getElementById("app-toast");
        if (!toast) {
            toast = document.createElement("div");
            toast.id = "app-toast";
            toast.className = "toast";
            document.body.appendChild(toast);
        }
        toast.textContent = message;
        toast.classList.add("show");
        window.clearTimeout(toast._hideTimer);
        toast._hideTimer = window.setTimeout(function () {
            toast.classList.remove("show");
        }, 2400);
    };

    function getCopySuccessMessage() {
        return getDashboardText("copySuccess", isRussianInterface()
            ? "Ссылка скопирована в буфер обмена."
            : "Link copied to clipboard.");
    }

    function getCopyFailureMessage() {
        return getDashboardText("copyFailure", isRussianInterface()
            ? "Не удалось скопировать. Скопируйте значение вручную."
            : "Copy failed. Please copy the value manually.");
    }

    function getCopyPromptMessage() {
        return getDashboardText("copyPrompt", isRussianInterface()
            ? "Скопируйте короткую ссылку вручную:"
            : "Copy the short link manually:");
    }

    async function copyWithClipboardApi(text) {
        if (!navigator.clipboard || typeof navigator.clipboard.writeText !== "function") {
            return false;
        }
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (error) {
            return false;
        }
    }

    function copyWithExecCommand(text) {
        const textarea = document.createElement("textarea");
        textarea.value = text;
        textarea.setAttribute("readonly", "readonly");
        textarea.style.position = "fixed";
        textarea.style.opacity = "0";
        textarea.style.pointerEvents = "none";
        textarea.style.left = "-9999px";
        textarea.style.top = "-9999px";
        document.body.appendChild(textarea);
        textarea.focus();
        textarea.select();
        textarea.setSelectionRange(0, text.length);

        let copied = false;
        try {
            copied = document.execCommand("copy");
        } catch (error) {
            copied = false;
        }

        document.body.removeChild(textarea);
        return copied;
    }

    function openManualCopyPrompt(text) {
        try {
            window.prompt(getCopyPromptMessage(), text);
        } catch (error) {
            window.appToast(getCopyFailureMessage());
        }
    }

    window.copyCreatedShortUrl = async function copyCreatedShortUrl() {
        const codeNode = document.getElementById("created-short-url");
        if (!codeNode) {
            return;
        }
        const text = (codeNode.dataset.copyValue || codeNode.textContent || "").trim();
        if (!text) {
            return;
        }

        let copied = await copyWithClipboardApi(text);
        if (!copied) {
            copied = copyWithExecCommand(text);
        }

        if (copied) {
            window.appToast(getCopySuccessMessage());
            return;
        }

        openManualCopyPrompt(text);
        window.appToast(getCopyFailureMessage());
    };

    function bindSoftDeleteConfirmations() {
        document.addEventListener("submit", function (event) {
            const form = event.target;
            if (!(form instanceof HTMLFormElement)) {
                return;
            }
            if (!form.hasAttribute("data-confirm")) {
                return;
            }
            const question = isRussianInterface()
                ? "Удалить эту ссылку?"
                : "Delete this link?";
            if (!window.confirm(question)) {
                event.preventDefault();
            }
        });
    }

    function bindHistoryDeleteMode() {
        const page = document.getElementById("dashboard-page");
        const form = document.getElementById("history-delete-form");
        const deleteButton = document.getElementById("history-delete-button");
        const cancelButton = document.getElementById("history-cancel-button");
        const confirmBackdrop = document.getElementById("history-confirm-backdrop");
        const confirmMessage = document.getElementById("history-confirm-message");
        const confirmYes = document.getElementById("history-confirm-yes");
        const confirmNo = document.getElementById("history-confirm-no");
        const checkboxes = Array.from(document.querySelectorAll(".history-select-checkbox"));

        if (!page || !form || !deleteButton || !cancelButton || !confirmBackdrop || !confirmMessage || !confirmYes || !confirmNo) {
            return;
        }

        const selectAtLeastOneMessage = getDashboardText("historyNoSelection", isRussianInterface()
            ? "Выберите хотя бы одну запись истории."
            : "Select at least one history record.");
        const activeConfirmMessage = getDashboardText("historyConfirmActive", isRussianInterface()
            ? "Удалить запись этой ссылки? Она всё ещё активна. После удаления короткая ссылка сразу перестанет работать."
            : "Delete this link record? It is still active. After deletion the short link will stop working immediately.");
        const disabledConfirmMessage = getDashboardText("historyConfirmDisabled", isRussianInterface()
            ? "Удалить запись этой ссылки? Её ещё можно снова включить. После удаления короткая ссылка сразу перестанет работать."
            : "Delete this link record? It can still be re-enabled. After deletion the short link will stop working immediately.");

        let pendingCheckbox = null;

        function isDeleteMode() {
            return page.classList.contains("history-mode");
        }

        function openConfirmDialog(checkbox) {
            pendingCheckbox = checkbox;
            confirmMessage.textContent = checkbox.dataset.confirmKind === "disabled"
                ? disabledConfirmMessage
                : activeConfirmMessage;
            confirmBackdrop.hidden = false;
            confirmBackdrop.setAttribute("aria-hidden", "false");
            document.body.classList.add("modal-open");
            confirmYes.focus();
        }

        function closeConfirmDialog() {
            confirmBackdrop.hidden = true;
            confirmBackdrop.setAttribute("aria-hidden", "true");
            document.body.classList.remove("modal-open");
            pendingCheckbox = null;
        }

        function clearSelections() {
            checkboxes.forEach(function (checkbox) {
                checkbox.checked = false;
                checkbox.dataset.confirmed = "";
            });
        }

        function enterDeleteMode() {
            if (checkboxes.length === 0) {
                window.appToast(selectAtLeastOneMessage);
                return;
            }
            page.classList.add("history-mode");
            deleteButton.classList.add("active-danger");
            cancelButton.hidden = false;
        }

        function exitDeleteMode() {
            closeConfirmDialog();
            clearSelections();
            page.classList.remove("history-mode");
            deleteButton.classList.remove("active-danger");
            cancelButton.hidden = true;
        }

        closeConfirmDialog();
        cancelButton.hidden = true;

        deleteButton.addEventListener("click", function () {
            if (!isDeleteMode()) {
                enterDeleteMode();
                return;
            }
            const selected = checkboxes.filter(function (checkbox) {
                return checkbox.checked;
            });
            if (selected.length === 0) {
                window.appToast(selectAtLeastOneMessage);
                return;
            }
            if (typeof form.requestSubmit === "function") {
                form.requestSubmit();
                return;
            }
            form.submit();
        });

        cancelButton.addEventListener("click", function () {
            exitDeleteMode();
        });

        checkboxes.forEach(function (checkbox) {
            checkbox.addEventListener("change", function () {
                if (!isDeleteMode()) {
                    checkbox.checked = false;
                    return;
                }
                if (!checkbox.checked) {
                    checkbox.dataset.confirmed = "";
                    return;
                }
                if (checkbox.dataset.requiresConfirm !== "true") {
                    return;
                }
                if (checkbox.dataset.confirmed === "true") {
                    return;
                }
                checkbox.checked = false;
                openConfirmDialog(checkbox);
            });
        });

        confirmYes.addEventListener("click", function () {
            if (pendingCheckbox) {
                pendingCheckbox.dataset.confirmed = "true";
                pendingCheckbox.checked = true;
                pendingCheckbox.focus();
            }
            closeConfirmDialog();
        });

        confirmNo.addEventListener("click", function () {
            if (pendingCheckbox) {
                pendingCheckbox.dataset.confirmed = "";
                pendingCheckbox.checked = false;
                pendingCheckbox.focus();
            }
            closeConfirmDialog();
        });

        confirmBackdrop.addEventListener("click", function (event) {
            if (event.target === confirmBackdrop) {
                confirmNo.click();
            }
        });

        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape" && !confirmBackdrop.hidden) {
                confirmNo.click();
            }
        });
    }

    onReady(function () {
        bindSoftDeleteConfirmations();
        bindHistoryDeleteMode();
    });
})();
