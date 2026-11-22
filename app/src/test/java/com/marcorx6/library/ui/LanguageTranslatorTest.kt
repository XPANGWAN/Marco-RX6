package com.marcorx6.library.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTranslatorTest {
    @Test
    fun everyTranslationEntryContainsEnglishFrenchAndSpanishVariants() {
        assertTrue(
            "Incomplete translation keys: ${LanguageTranslator.incompleteTranslationKeys()}",
            LanguageTranslator.incompleteTranslationKeys().isEmpty()
        )
    }

    @Test
    fun translatesFullScreenUpdateActionsIntoAllSupportedLanguages() {
        assertEquals("Update now", LanguageTranslator.translate("تحديث الآن", "en"))
        assertEquals("Mettre à jour", LanguageTranslator.translate("تحديث الآن", "fr"))
        assertEquals("Actualizar ahora", LanguageTranslator.translate("تحديث الآن", "es"))
        assertEquals("تحديث الآن", LanguageTranslator.translate("تحديث الآن", "ar"))
    }

    @Test
    fun translatesStartupAndDownloadNotificationStringsIntoAllSupportedLanguages() {
        val cases = listOf(
            "بدء تشغيل النظام" to listOf("SYSTEM STARTUP", "DÉMARRAGE DU SYSTÈME", "INICIO DEL SISTEMA"),
            "تهيئة واجهة التطبيق" to listOf("Preparing the app interface", "Préparation de l’interface", "Preparando la interfaz de la aplicación"),
            "تجهيز مكتبتك" to listOf("Preparing your library", "Préparation de votre bibliothèque", "Preparando tu biblioteca"),
            "تحميل عناصر البداية" to listOf("Loading startup components", "Chargement des éléments de démarrage", "Cargando los componentes iniciales"),
            "ضبط المظهر والتفضيلات" to listOf("Applying appearance and preferences", "Application de l’apparence et des préférences", "Aplicando la apariencia y las preferencias"),
            "إتمام التجهيزات" to listOf("Finishing setup", "Finalisation de la configuration", "Terminando la configuración"),
            "لحظات وننطلق" to listOf("Almost ready", "Presque prêt", "Casi listo"),
            "التنزيل متوقف مؤقتاً" to listOf("Download paused", "Téléchargement en pause", "Descarga pausada")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesGameDownloadSectionHeadingIntoAllSupportedLanguages() {
        assertEquals("Download links", LanguageTranslator.translate("روابط التحميل", "en"))
        assertEquals("Liens de téléchargement", LanguageTranslator.translate("روابط التحميل", "fr"))
        assertEquals("Enlaces de descarga", LanguageTranslator.translate("روابط التحميل", "es"))
    }

    @Test
    fun translatesFavoriteAccessibilityActionsIntoAllSupportedLanguages() {
        assertEquals("Add to favorites", LanguageTranslator.translate("إضافة إلى المفضلة", "en"))
        assertEquals("Ajouter aux favoris", LanguageTranslator.translate("إضافة إلى المفضلة", "fr"))
        assertEquals("Añadir a favoritos", LanguageTranslator.translate("إضافة إلى المفضلة", "es"))
        assertEquals("Remove from favorites", LanguageTranslator.translate("إزالة من المفضلة", "en"))
        assertEquals("Retirer des favoris", LanguageTranslator.translate("إزالة من المفضلة", "fr"))
        assertEquals("Quitar de favoritos", LanguageTranslator.translate("إزالة من المفضلة", "es"))
    }

    @Test
    fun translatesStaticLabelsIntoAllSupportedLanguages() {
        assertEquals("Downloads", LanguageTranslator.translate("التنزيلات", "en"))
        assertEquals("Téléchargements", LanguageTranslator.translate("التنزيلات", "fr"))
        assertEquals("Descargas", LanguageTranslator.translate("التنزيلات", "es"))
    }

    @Test
    fun translatesShieldAndAdBlockingSettingsIntoAllSupportedLanguages() {
        val cases = listOf(
            "إعدادات الدرع والحماية" to listOf("Shield & protection settings", "Paramètres du bouclier et de la protection", "Ajustes del escudo y la protección"),
            "حظر المواقع يدوياً" to listOf("Block websites manually", "Bloquer des sites manuellement", "Bloquear sitios manualmente"),
            "مثال: example.com" to listOf("Example: example.com", "Exemple : example.com", "Ejemplo: example.com"),
            "لا توجد مواقع محظورة يدوياً" to listOf("No manually blocked websites", "Aucun site bloqué manuellement", "No hay sitios bloqueados manualmente"),
            "إضافة إلى قائمة الحظر" to listOf("Add to blocklist", "Ajouter à la liste de blocage", "Añadir a la lista de bloqueo"),
            "إزالة" to listOf("Remove", "Supprimer", "Eliminar"),
            "أدخل اسم نطاق صحيحاً مثل example.com" to listOf("Enter a valid domain, such as example.com", "Saisissez un nom de domaine valide, par exemple example.com", "Introduce un dominio válido, por ejemplo example.com"),
            "وصلت إلى الحد الأقصى للمواقع المحظورة" to listOf("You have reached the blocked websites limit", "Vous avez atteint la limite de sites bloqués", "Has alcanzado el límite de sitios bloqueados")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesBrandNameInAllSupportedLanguagesWithoutIndexOverflow() {
        assertEquals("MARCO RX6", LanguageTranslator.translate("MARCO RX6", "en"))
        assertEquals("MARCO RX6", LanguageTranslator.translate("MARCO RX6", "fr"))
        assertEquals("MARCO RX6", LanguageTranslator.translate("MARCO RX6", "es"))
        assertEquals("MARCO RX6", LanguageTranslator.translate("MARCO RX6", "ar"))
    }

    @Test
    fun translatesDiagnosticExportFeedbackInAllSupportedLanguages() {
        val preparing = "جارٍ تجهيز سجل التشخيص…"
        assertEquals("Preparing diagnostic log…", LanguageTranslator.translate(preparing, "en"))
        assertEquals("Préparation du journal de diagnostic…", LanguageTranslator.translate(preparing, "fr"))
        assertEquals("Preparando el registro de diagnóstico…", LanguageTranslator.translate(preparing, "es"))

        val sharingError = "تعذر مشاركة سجل التشخيص"
        assertEquals("Could not share the diagnostic log", LanguageTranslator.translate(sharingError, "en"))
        assertEquals("Impossible de partager le journal de diagnostic", LanguageTranslator.translate(sharingError, "fr"))
        assertEquals("No se pudo compartir el registro de diagnóstico", LanguageTranslator.translate(sharingError, "es"))
    }

    @Test
    fun translatesFormalArabicEmptyState() {
        assertEquals("No downloads yet.", LanguageTranslator.translate("لا توجد تنزيلات حالياً.", "en"))
        assertEquals("Aucun téléchargement pour le moment.", LanguageTranslator.translate("لا توجد تنزيلات حالياً.", "fr"))
        assertEquals("Aún no hay descargas.", LanguageTranslator.translate("لا توجد تنزيلات حالياً.", "es"))
        assertEquals("لا توجد تنزيلات حالياً.", LanguageTranslator.translate("لا توجد تنزيلات حالياً.", "ar"))
    }

    @Test
    fun translatesDynamicServerAndDriveErrorsWithoutLosingDetails() {
        assertEquals(
            "The server rejected the download URL (HTTP 403)",
            LanguageTranslator.translate("الخادم رفض رابط التنزيل (HTTP 403)", "en")
        )
        assertEquals(
            "Impossible d’envoyer l’image vers Google Drive (403): Forbidden",
            LanguageTranslator.translate("فشل رفع الصورة إلى Google Drive (403): Forbidden", "fr")
        )
        assertEquals(
            "No se pudo verificar la URL de la imagen (HTTP 404, type: text/html)",
            LanguageTranslator.translate("تعذر التحقق من رابط الصورة (HTTP 404، النوع: text/html)", "es")
        )
    }
    @Test
    fun translatesAllLibrarySectionHeadingsIntoSupportedLanguages() {
        val search = "MARCO  /  البحث الذكي"
        assertEquals("MARCO  /  SMART SEARCH", LanguageTranslator.translate(search, "en"))
        assertEquals("MARCO  /  RECHERCHE INTELLIGENTE", LanguageTranslator.translate(search, "fr"))
        assertEquals("MARCO  /  BÚSQUEDA INTELIGENTE", LanguageTranslator.translate(search, "es"))

        val hub = "MARCO  /  مركز الألعاب الرسمي"
        assertEquals("MARCO  /  OFFICIAL GAME HUB", LanguageTranslator.translate(hub, "en"))
        assertEquals("MARCO  /  CENTRE DE JEUX OFFICIEL", LanguageTranslator.translate(hub, "fr"))
        assertEquals("MARCO  /  CENTRO OFICIAL DE JUEGOS", LanguageTranslator.translate(hub, "es"))

        val games = "MARCO  /  الألعاب"
        assertEquals("MARCO  /  GAMES", LanguageTranslator.translate(games, "en"))
        assertEquals("MARCO  /  JEUX", LanguageTranslator.translate(games, "fr"))
        assertEquals("MARCO  /  JUEGOS", LanguageTranslator.translate(games, "es"))
    }


    @Test
    fun normalizesLegacyColloquialArabicToFormalArabicAcrossTheApp() {
        val cases = mapOf(
            "ما كايناش تنزيلات دابا" to "لا توجد تنزيلات حالياً.",
            "اختار لعبة، ومن صفحة التفاصيل أضف رابط التحميل باش يبان هنا." to "اختر لعبة، ثم أضف رابط التنزيل من صفحة التفاصيل ليظهر هنا.",
            "الصورة كبيرة بزاف، اختار صورة أصغر" to "الصورة كبيرة جداً. اختر صورة أصغر.",
            "كلمة المرور خاصها تكون 6 أحرف على الأقل" to "يجب ألا تقل كلمة المرور عن 6 أحرف.",
            "ما كاين حتى حساب لحذفه دابا" to "لا يوجد حساب لحذفه حالياً.",
            "طريقة التحقق من هاد الحساب غير مدعومة حالياً. تواصل مع الدعم." to "طريقة التحقق من هذا الحساب غير مدعومة حالياً. يُرجى التواصل مع الدعم.",
            "دخل كلمة المرور الحالية" to "أدخل كلمة المرور الحالية.",
            "هاد العملية نهائية: غادي نحذف ملف الحساب والإعدادات المحفوظة فـ Firebase، ومن بعد حساب Firebase نفسه. مكتبة الألعاب والملفات المحلية فالتيليفون، وحساب Google المسؤول ومحتوى Blogger وDrive، ما غاديش نمسوهم." to "هذا الإجراء نهائي: سنحذف ملف الحساب والإعدادات المحفوظة في Firebase، ثم حساب Firebase نفسه. لن نحذف مكتبة الألعاب أو الملفات المحلية على الهاتف أو حساب Google المسؤول أو محتوى Blogger وDrive.",
            "دخل كلمة المرور الحالية باش نتحققو من هويتك قبل الحذف." to "أدخل كلمة المرور الحالية للتحقق من هويتك قبل الحذف.",
            "النبذة خاصها تكون أقل من 160 حرف" to "يجب ألا تتجاوز النبذة 160 حرفاً.",
            "إلا كان الحساب موجود، غادي توصلك رسالة استرجاع كلمة المرور" to "إذا كان الحساب موجوداً، فستصلك رسالة لإعادة تعيين كلمة المرور.",
            "الاسم ضروري وخاصو يكون أقل من 40 حرف" to "الاسم مطلوب، ويجب ألا يتجاوز 40 حرفاً.",
            "كنحمّلو معلومات الملف الشخصي، عاود المحاولة بعد لحظة" to "جارٍ تحميل معلومات الملف الشخصي؛ يُرجى إعادة المحاولة بعد لحظة."
        )
        cases.forEach { (legacy, formal) ->
            assertEquals(formal, LanguageTranslator.translate(legacy, "ar"))
            assertTrue(LanguageTranslator.translate(legacy, "en") != legacy)
            assertTrue(LanguageTranslator.translate(legacy, "fr") != legacy)
            assertTrue(LanguageTranslator.translate(legacy, "es") != legacy)
        }
    }

    @Test
    fun translatesPreviouslyUncoveredAccountSocialAndRepositoryMessages() {
        val cases = mapOf(
            "لا توجد جلسة مستخدم" to listOf("No active user session", "Aucune session utilisateur active", "No hay una sesión de usuario activa"),
            "سجّل الدخول أولاً" to listOf("Sign in first", "Connectez-vous d’abord", "Inicia sesión primero"),
            "التقييم متاح للمستخدمين المسجلين فقط" to listOf("Ratings are available to registered users only", "Les évaluations sont réservées aux utilisateurs inscrits", "Las valoraciones están disponibles solo para usuarios registrados"),
            "التقييم يجب أن يكون بين نجمة و5 نجوم" to listOf("The rating must be between 1 and 5 stars", "La note doit être comprise entre 1 et 5 étoiles", "La valoración debe estar entre 1 y 5 estrellas"),
            "تعذر التحقق من المتابعة المتبادلة" to listOf("Could not verify mutual following", "Impossible de vérifier le suivi mutuel", "No se pudo verificar el seguimiento mutuo"),
            "معرّف المستخدم غير صالح" to listOf("Invalid user ID", "Identifiant utilisateur invalide", "ID de usuario no válido"),
            "تعذر التحقق من صلاحية المراسلة" to listOf("Could not verify messaging permission", "Impossible de vérifier l’autorisation de messagerie", "No se pudo verificar el permiso de mensajería"),
            "يمكنك المراسلة فقط عندما يتابع كل منكما الآخر" to listOf("You can message only when you follow each other", "Vous pouvez échanger des messages uniquement si vous vous suivez mutuellement", "Solo pueden enviarse mensajes cuando se siguen mutuamente"),
            "بيانات المشاركين في المحادثة غير صالحة" to listOf("Conversation participant data is invalid", "Les données des participants à la conversation sont invalides", "Los datos de los participantes de la conversación no son válidos"),
            "تعذر إنشاء المحادثة" to listOf("Could not create the conversation", "Impossible de créer la conversation", "No se pudo crear la conversación"),
            "الرسالة غير صالحة أو تتجاوز الحد المسموح" to listOf("The message is invalid or exceeds the allowed limit", "Le message est invalide ou dépasse la limite autorisée", "El mensaje no es válido o supera el límite permitido"),
            "تعذر تحميل بيانات المحادثة والمرسل" to listOf("Could not load conversation and sender data", "Impossible de charger les données de la conversation et de l’expéditeur", "No se pudieron cargar los datos de la conversación y del remitente"),
            "معرّف المحادثة غير صالح" to listOf("Invalid conversation ID", "Identifiant de conversation invalide", "ID de conversación no válido"),
            "الرسالة غير صالحة" to listOf("Invalid message", "Message invalide", "Mensaje no válido"),
            "تعذر تحميل الرسالة" to listOf("Could not load the message", "Impossible de charger le message", "No se pudo cargar el mensaje"),
            "تعذر تحميل المحادثة" to listOf("Could not load the conversation", "Impossible de charger la conversation", "No se pudo cargar la conversación"),
            "تم حذف رسالة" to listOf("A message was deleted", "Un message a été supprimé", "Se eliminó un mensaje"),
            "معرّف الحساب غير صالح" to listOf("Invalid account ID", "Identifiant de compte invalide", "ID de cuenta no válido"),
            "إشعار غير صالح" to listOf("Invalid notification", "Notification invalide", "Notificación no válida"),
            "تعذر تحميل الإشعارات" to listOf("Could not load notifications", "Impossible de charger les notifications", "No se pudieron cargar las notificaciones"),
            "تعذر قراءة ملف المستخدم" to listOf("Could not read the user profile", "Impossible de lire le profil utilisateur", "No se pudo leer el perfil del usuario"),
            "الصورة العامة كبيرة جداً؛ اختر صورة أصغر." to listOf("The public profile image is too large; choose a smaller image.", "L’image du profil public est trop volumineuse ; choisissez une image plus petite.", "La imagen del perfil público es demasiado grande; elige una imagen más pequeña."),
            "تعذر البحث عن اسم المستخدم" to listOf("Could not look up the username", "Impossible de rechercher le nom d’utilisateur", "No se pudo buscar el nombre de usuario"),
            "اسم المستخدم يجب أن يتكون من 3 إلى 20 حرفاً إنجليزياً أو رقماً أو _" to listOf("The username must contain 3–20 English letters, digits, or underscores", "Le nom d’utilisateur doit contenir de 3 à 20 lettres anglaises, chiffres ou tirets bas", "El nombre de usuario debe contener entre 3 y 20 letras inglesas, números o guiones bajos"),
            "اسم المستخدم مستخدم بالفعل. اختر اسماً آخر." to listOf("This username is already taken. Choose another.", "Ce nom d’utilisateur est déjà utilisé. Choisissez-en un autre.", "Este nombre de usuario ya está en uso. Elige otro."),
            "تعذر حجز اسم المستخدم" to listOf("Could not reserve the username", "Impossible de réserver le nom d’utilisateur", "No se pudo reservar el nombre de usuario"),
            "معرّف المتابعة غير صالح" to listOf("Invalid follow ID", "Identifiant d’abonnement invalide", "ID de seguimiento no válido"),
            "متابع جديد" to listOf("New follower", "Nouvel abonné", "Nuevo seguidor"),
            "بدأ هذا المستخدم بمتابعتك" to listOf("This user started following you", "Cet utilisateur a commencé à vous suivre", "Este usuario ha empezado a seguirte"),
            "تعذر تحميل حالة المتابعة" to listOf("Could not load follow status", "Impossible de charger le statut du suivi", "No se pudo cargar el estado del seguimiento"),
            "تعذر تحميل إحصائيات المتابعة" to listOf("Could not load follow statistics", "Impossible de charger les statistiques de suivi", "No se pudieron cargar las estadísticas de seguimiento"),
            "تعذر قراءة إعدادات الملف" to listOf("Could not read profile settings", "Impossible de lire les paramètres du profil", "No se pudieron leer los ajustes del perfil"),
            "تعذر تحديث إحصائيات المتابعة" to listOf("Could not update follow statistics", "Impossible de mettre à jour les statistiques de suivi", "No se pudieron actualizar las estadísticas de seguimiento"),
            "تعذر قراءة الملف العام" to listOf("Could not read the public profile", "Impossible de lire le profil public", "No se pudo leer el perfil público"),
            "تعذر تحميل المتابعين" to listOf("Could not load followers", "Impossible de charger les abonnés", "No se pudieron cargar los seguidores"),
            "تعذر تحميل قائمة المتابعة" to listOf("Could not load the following list", "Impossible de charger la liste des abonnements", "No se pudo cargar la lista de seguidos"),
            "تعذر تحميل علاقات المتابعة" to listOf("Could not load follow relationships", "Impossible de charger les relations de suivi", "No se pudieron cargar las relaciones de seguimiento"),
            "تعذر حذف علاقات المتابعة" to listOf("Could not delete follow relationships", "Impossible de supprimer les relations de suivi", "No se pudieron eliminar las relaciones de seguimiento"),
            "تعذر تحميل تقييمات الحساب" to listOf("Could not load account ratings", "Impossible de charger les évaluations du compte", "No se pudieron cargar las valoraciones de la cuenta"),
            "تعذر حذف تقييمات الحساب" to listOf("Could not delete account ratings", "Impossible de supprimer les évaluations du compte", "No se pudieron eliminar las valoraciones de la cuenta"),
            "تعذر حذف الملف العام" to listOf("Could not delete the public profile", "Impossible de supprimer le profil public", "No se pudo eliminar el perfil público"),
            "تعذر حذف الحساب واسم المستخدم" to listOf("Could not delete the account and username", "Impossible de supprimer le compte et le nom d’utilisateur", "No se pudo eliminar la cuenta y el nombre de usuario"),
            "إشعارات MARCO" to listOf("MARCO notifications", "Notifications MARCO", "Notificaciones de MARCO"),
            "رسائل ومتابعون وتحديثات الألعاب" to listOf("Messages, followers and game updates", "Messages, abonnés et mises à jour des jeux", "Mensajes, seguidores y actualizaciones de juegos"),
            "مجتمع MARCO" to listOf("MARCO Community", "Communauté MARCO", "Comunidad MARCO"),
            "أنت تتابع هذا المستخدم. اضغط لإلغاء المتابعة" to listOf("You follow this user. Tap to unfollow.", "Vous suivez cet utilisateur. Touchez pour vous désabonner.", "Sigues a este usuario. Toca para dejar de seguirlo."),
            "اضغط لمتابعة هذا المستخدم" to listOf("Tap to follow this user", "Touchez pour suivre cet utilisateur", "Toca para seguir a este usuario"),
            "فشل رفع إحدى صور المعرض" to listOf("One of the gallery images could not be uploaded", "L’envoi de l’une des images de la galerie a échoué", "No se pudo subir una de las imágenes de la galería"),
            "الألعاب العربية" to listOf("Arabic games", "Jeux en arabe", "Juegos en árabe"),
            "العاب عربية" to listOf("Arabic games", "Jeux en arabe", "Juegos en árabe"),
            "العاب" to listOf("Games", "Jeux", "Juegos"),
            "التطبيقات والادوات" to listOf("Apps & Tools", "Applications et outils", "Aplicaciones y herramientas"),
            "محاكيات" to listOf("Emulators", "Émulateurs", "Emuladores"),
            "افتارات" to listOf("Avatars", "Avatars", "Avatares"),
            "ثيمات" to listOf("Themes", "Thèmes", "Temas")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
        }
    }

    @Test
    fun normalizesLegacySectionLabelsToFormalArabic() {
        assertEquals("الألعاب", LanguageTranslator.translate("العاب", "ar"))
        assertEquals("الألعاب العربية", LanguageTranslator.translate("العاب عربية", "ar"))
        assertEquals("التطبيقات والأدوات", LanguageTranslator.translate("التطبيقات والادوات", "ar"))
        assertEquals("صور رمزية", LanguageTranslator.translate("افتارات", "ar"))
        assertEquals("السمات", LanguageTranslator.translate("ثيمات", "ar"))
        assertEquals("Avatars", LanguageTranslator.translate("صور رمزية", "en"))
        assertEquals("Thèmes", LanguageTranslator.translate("السمات", "fr"))
    }

    @Test
    fun preservesUnknownTextAndUnsupportedLanguageCodes() {
        val source = "نص جديد غير موجود في القاموس"
        assertEquals(source, LanguageTranslator.translate(source, "en"))
        assertEquals(source, LanguageTranslator.translate(source, "fr"))
        assertEquals(source, LanguageTranslator.translate(source, "es"))
        assertEquals(source, LanguageTranslator.translate(source, "de"))
        assertEquals(source, LanguageTranslator.translate(source, ""))
    }

    @Test
    fun translatesRatingInterfaceIntoAllSupportedLanguages() {
        val source = "قيّم هذه اللعبة"
        assertEquals("Rate this game", LanguageTranslator.translate(source, "en"))
        assertEquals("Évaluer ce jeu", LanguageTranslator.translate(source, "fr"))
        assertEquals("Califica este juego", LanguageTranslator.translate(source, "es"))
        assertEquals(source, LanguageTranslator.translate(source, "ar"))
    }

    @Test
    fun keepsTheCanonicalTranslationForPreviouslyDuplicatedKeys() {
        assertEquals(
            "Personaliza MARCO a tu manera",
            LanguageTranslator.translate("خصص تجربة MARCO بالطريقة التي تناسبك", "es")
        )
        assertEquals(
            "Volver a intentar",
            LanguageTranslator.translate("إعادة المحاولة", "es")
        )
        assertEquals(
            "Todo listo",
            LanguageTranslator.translate("جاهز للانطلاق", "es")
        )
        assertEquals(
            "Previous",
            LanguageTranslator.translate("السابق", "en")
        )
        assertEquals(
            "Interface animations and transitions",
            LanguageTranslator.translate("حركات وانتقالات الواجهة", "en")
        )
        assertEquals(
            "Interface transitions enabled",
            LanguageTranslator.translate("تم تفعيل انتقالات الواجهة", "en")
        )
    }

    @Test
    fun translatesDynamicBlockedRequestCountWithoutChangingItsValue() {
        assertEquals(
            "Ads and trackers blocked: 17",
            LanguageTranslator.translate("الإعلانات والمتتبعات المحجوبة: 17", "en")
        )
        assertEquals(
            "Publicités et traceurs bloqués : 17",
            LanguageTranslator.translate("الإعلانات والمتتبعات المحجوبة: 17", "fr")
        )
        assertEquals(
            "Anuncios y rastreadores bloqueados: 17",
            LanguageTranslator.translate("الإعلانات والمتتبعات المحجوبة: 17", "es")
        )
    }

    @Test
    fun translatesBloggerComposerValidationMessages() {
        assertEquals(
            "Enter both the link name and its URL",
            LanguageTranslator.translate("أدخل اسم الرابط وعنوانه معاً", "en")
        )
        assertEquals(
            "Ajoutez au moins un lien de téléchargement avant l’aperçu",
            LanguageTranslator.translate("أضف رابط تحميل واحداً على الأقل قبل المعاينة", "fr")
        )
        assertEquals(
            "Añade al menos un enlace de descarga antes de publicar",
            LanguageTranslator.translate("أضف رابط تحميل واحداً على الأقل قبل النشر", "es")
        )
    }

    @Test
    fun translatesBloggerSyncStatus() {
        assertEquals(
            "Connecting to the MARCO RX6 blog...",
            LanguageTranslator.translate("جارٍ الاتصال بمدونة MARCO RX6...", "en")
        )
        assertEquals(
            "Connexion au blog MARCO RX6...",
            LanguageTranslator.translate("جارٍ الاتصال بمدونة MARCO RX6...", "fr")
        )
        assertEquals(
            "Conectando con el blog MARCO RX6...",
            LanguageTranslator.translate("جارٍ الاتصال بمدونة MARCO RX6...", "es")
        )
    }

    @Test
    fun translatesDynamicOnboardingStepNumbers() {
        assertEquals("Step 2", LanguageTranslator.translate("خطوة 2", "en"))
        assertEquals("Étape 3", LanguageTranslator.translate("خطوة 3", "fr"))
        assertEquals("Paso 4", LanguageTranslator.translate("خطوة 4", "es"))
        assertEquals("خطوة 4", LanguageTranslator.translate("خطوة 4", "ar"))
    }

    @Test
    fun translatesDownloadBrowserControlsAndValidation() {
        assertEquals("Download browser", LanguageTranslator.translate("متصفح التحميل", "en"))
        assertEquals("Rechercher ou saisir l’adresse d’un site", LanguageTranslator.translate("ابحث أو أدخل عنوان الموقع", "fr"))
        assertEquals("La URL de la página no es válida o no es segura", LanguageTranslator.translate("رابط الصفحة غير صالح أو غير آمن", "es"))
        assertEquals("Forward", LanguageTranslator.translate("تقدم", "en"))
        assertEquals("Open externally", LanguageTranslator.translate("فتح خارجياً", "en"))
    }

    @Test
    fun translatesPublishingStudioAndAccountVerificationStrings() {
        assertEquals(
            "Enter the game information and links, then preview the post before publishing.",
            LanguageTranslator.translate("أدخل معلومات اللعبة وروابطها، ثم راجع شكل المقال قبل النشر.", "en")
        )
        assertEquals("Titre et libellés de l’article", LanguageTranslator.translate("العنوان وأقسام المقال", "fr"))
        assertEquals("Direct download links", LanguageTranslator.translate("روابط التحميل المباشرة", "en"))
        assertEquals("Nom du lien (exemple : Google Drive)", LanguageTranslator.translate("اسم الرابط (مثال: Google Drive)", "fr"))
        assertEquals("HTTPS download URL", LanguageTranslator.translate("رابط التحميل HTTPS", "en"))
        assertEquals("Download link", LanguageTranslator.translate("رابط تحميل", "en"))
        assertEquals("Supprimer ce lien", LanguageTranslator.translate("إزالة هذا الرابط", "fr"))
        assertEquals("Add another link", LanguageTranslator.translate("إضافة رابط جديد", "en"))
        assertEquals("Back from the publishing studio", LanguageTranslator.translate("الرجوع من استوديو النشر", "en"))
        assertEquals("Estudio de publicación", LanguageTranslator.translate("استوديو النشر", "es"))
        assertEquals("Edit post", LanguageTranslator.translate("تحرير المقال", "en"))
        assertEquals("El enlace debe comenzar por HTTPS", LanguageTranslator.translate("يجب أن يبدأ الرابط بـ HTTPS", "es"))
        assertEquals(
            "Enter your current password to verify your identity before deletion.",
            LanguageTranslator.translate("أدخل كلمة المرور الحالية للتحقق من هويتك قبل الحذف.", "en")
        )
    }

    @Test
    fun translatesNewVectorNavigationAccessibilityLabels() {
        assertEquals("Open section", LanguageTranslator.translate("فتح القسم", "en"))
        assertEquals("Ouvrir la section", LanguageTranslator.translate("فتح القسم", "fr"))
        assertEquals("Abrir sección", LanguageTranslator.translate("فتح القسم", "es"))
        assertEquals("Open profile", LanguageTranslator.translate("فتح الملف الشخصي", "en"))
        assertEquals("Ouvrir le profil", LanguageTranslator.translate("فتح الملف الشخصي", "fr"))
        assertEquals("Abrir perfil", LanguageTranslator.translate("فتح الملف الشخصي", "es"))
    }

    @Test
    fun translatesNewAccountAndCommunityLabelsIntoAllSupportedLanguages() {
        val cases = listOf(
            "ملف MARCO الشخصي" to listOf("MARCO profile", "Profil MARCO", "Perfil de MARCO"),
            "مرحباً بك في مجتمع MARCO" to listOf("Welcome to the MARCO community", "Bienvenue dans la communauté MARCO", "Te damos la bienvenida a la comunidad MARCO"),
            "تسجيل الخروج من MARCO؟" to listOf("Sign out of MARCO?", "Se déconnecter de MARCO ?", "¿Cerrar sesión en MARCO?"),
            "أنشئ حساب MARCO لمتابعة هذا الملف الشخصي" to listOf("Create a MARCO account to follow this profile", "Créez un compte MARCO pour suivre ce profil", "Crea una cuenta MARCO para seguir este perfil"),
            "+ تبويب جديد" to listOf("+ New tab", "+ Nouvel onglet", "+ Nueva pestaña"),
            "رفضت قواعد Firestore قراءة قائمة المتابعين (PERMISSION_DENIED)." to listOf("Firestore rules denied access to the followers list (PERMISSION_DENIED).", "Les règles Firestore ont refusé l’accès à la liste des abonnés (PERMISSION_DENIED).", "Las reglas de Firestore rechazaron el acceso a la lista de seguidores (PERMISSION_DENIED).")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }
    @Test
    fun translatesExpandedAppWideControlsIntoAllSupportedLanguages() {
        val cases = listOf(
            "التشخيص وسجل الأخطاء" to listOf("Diagnostics & error log", "Diagnostics et journal d’erreurs", "Diagnóstico y registro de errores"),
            "عدد التنزيلات المتزامنة" to listOf("Concurrent downloads", "Téléchargements simultanés", "Descargas simultáneas"),
            "إيقاف الكل" to listOf("Pause all", "Tout mettre en pause", "Pausar todo"),
            "اختيار صورة الغلاف من الهاتف" to listOf("Choose cover image from phone", "Choisir une image de couverture sur le téléphone", "Elegir imagen de portada del teléfono"),
            "اختر القسم الذي تريد تصفحه" to listOf("Choose the section you want to browse", "Choisissez la section à parcourir", "Elige la sección que quieres explorar"),
            "MARCO  /  PERSONALIZATION" to listOf("MARCO  /  PERSONALIZATION", "MARCO  /  PERSONNALISATION", "MARCO  /  PERSONALIZACIÓN")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesDownloadDialogsAndFileControlsIntoAllSupportedLanguages() {
        val cases = listOf(
            "إضافة إلى التنزيلات" to listOf("Add to downloads", "Ajouter aux téléchargements", "Añadir a descargas"),
            "رابط التنزيل" to listOf("Download link", "Lien de téléchargement", "Enlace de descarga"),
            "اسم الملف" to listOf("File name", "Nom du fichier", "Nombre del archivo"),
            "مجلد الحفظ" to listOf("Save folder", "Dossier d’enregistrement", "Carpeta de guardado"),
            "لا توجد تنزيلات في القائمة" to listOf("There are no downloads in the list", "Aucun téléchargement dans la liste", "No hay descargas en la lista")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
        }
    }

    @Test
    fun translatesPreviouslyUntranslatedAccountNotificationAndBrowserLabels() {
        val cases = listOf(
            "لا توجد رسائل جديدة حالياً." to listOf("No new messages right now.", "Aucun nouveau message pour le moment.", "No hay mensajes nuevos por ahora."),
            "تعذر تحميل الإشعارات. تحقق من الاتصال." to listOf("Could not load notifications. Check your connection.", "Impossible de charger les notifications. Vérifiez votre connexion.", "No se pudieron cargar las notificaciones. Comprueba tu conexión."),
            "لا يوجد متابعون بعد." to listOf("No followers yet.", "Aucun abonné pour le moment.", "Aún no hay seguidores."),
            "لغة المحتوى" to listOf("Content language", "Langue du contenu", "Idioma del contenido"),
            "مسح البيانات" to listOf("Clear data", "Effacer les données", "Borrar datos"),
            "التبويبات المفتوحة" to listOf("Open tabs", "Onglets ouverts", "Pestañas abiertas"),
            "أدخل رابطاً أو عبارة للبحث" to listOf("Enter a URL or search term", "Saisissez une URL ou un terme de recherche", "Introduce una URL o un término de búsqueda"),
            "إعدادات حجب الإعلانات" to listOf("Ad-blocking settings", "Paramètres de blocage des publicités", "Ajustes del bloqueo de anuncios")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesDynamicBrowserAndAdBlockLabels() {
        assertEquals("Tab 3", LanguageTranslator.translate("تبويب 3", "en"))
        assertEquals("Onglet 4", LanguageTranslator.translate("التبويب 4", "fr"))
        assertEquals("Pestaña 5", LanguageTranslator.translate("تبويب 5", "es"))
        assertEquals("Ads blocked: 12", LanguageTranslator.translate("عدد الإعلانات المحجوبة: 12", "en"))
        assertEquals("Publicités bloquées : 8", LanguageTranslator.translate("عدد الإعلانات المحجوبة: 8", "fr"))
        assertEquals("No se pudo cargar la lista (403)", LanguageTranslator.translate("تعذر تحميل القائمة (403)", "es"))
    }

    @Test
    fun translatesSystemAlertsAndNewGameNotificationLabels() {
        val cases = listOf(
            "تنبيهات النظام" to listOf("System alerts", "Alertes système", "Alertas del sistema"),
            "لعبة جديدة في المكتبة" to listOf("New game in the library", "Nouveau jeu dans la bibliothèque", "Nuevo juego en la biblioteca"),
            "لا توجد تنبيهات عامة حالياً." to listOf("No system alerts right now.", "Aucune alerte système pour le moment.", "No hay alertas del sistema por ahora.")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesBlackAndSilverPaletteName() {
        assertEquals("Black & Silver", LanguageTranslator.translate("أسود فضي", "en"))
        assertEquals("Noir et argent", LanguageTranslator.translate("أسود فضي", "fr"))
        assertEquals("Negro y plata", LanguageTranslator.translate("أسود فضي", "es"))
        assertEquals("أسود فضي", LanguageTranslator.translate("أسود فضي", "ar"))
    }

    @Test
    fun translatesRemainingBrowserAndGoogleAccountStatusMessages() {
        assertEquals("Tab", LanguageTranslator.translate("تبويب", "en"))
        assertEquals("Onglet", LanguageTranslator.translate("تبويب", "fr"))
        assertEquals("Pestaña", LanguageTranslator.translate("تبويب", "es"))

        val refresh = "تعذر تحديث معلومات اللعبة من المصدر؛ تحقق من الاتصال بالإنترنت"
        assertEquals("Could not refresh the game information from its source; check your internet connection", LanguageTranslator.translate(refresh, "en"))
        assertEquals("Impossible d’actualiser les informations du jeu depuis sa source ; vérifiez votre connexion Internet", LanguageTranslator.translate(refresh, "fr"))
        assertEquals("No se pudo actualizar la información del juego desde su fuente; comprueba tu conexión a Internet", LanguageTranslator.translate(refresh, "es"))

        val connected = "تم ربط حساب Google؛ جارٍ تحميل مدونة MARCO والتحقق من صلاحيات النشر…"
        assertEquals("Google account connected; loading the MARCO blog and checking publishing permissions…", LanguageTranslator.translate(connected, "en"))
        assertEquals("Compte Google connecté ; chargement du blog MARCO et vérification des autorisations de publication…", LanguageTranslator.translate(connected, "fr"))
        assertEquals("Cuenta de Google conectada; cargando el blog MARCO y comprobando los permisos de publicación…", LanguageTranslator.translate(connected, "es"))
    }


    @Test
    fun translatesPlatformNavigationIntoAllSupportedLanguages() {
        val cases = listOf(
            "المنصات" to listOf("Platforms", "Plateformes", "Plataformas"),
            "اختر المنصة لعرض أقسامها الفرعية" to listOf(
                "Choose a platform to view its subcategories",
                "Choisissez une plateforme pour afficher ses sous-catégories",
                "Elige una plataforma para ver sus subcategorías"
            ),
            "رجوع إلى المنصات" to listOf(
                "Back to platforms", "Retour aux plateformes", "Volver a las plataformas"
            ),
            "اختر القسم الفرعي" to listOf(
                "Choose a subcategory", "Choisissez une sous-catégorie", "Elige una subcategoría"
            ),
            "PS3 Arabic" to listOf("PS3 Arabic", "PS3 arabe", "PS3 árabe"),
            "تصفح أقسام المنصات" to listOf(
                "Browse platform sections",
                "Parcourir les sections des plateformes",
                "Explorar las secciones de plataformas"
            )
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesDynamicOnboardingStepNumbersWithoutAllocatingPatternsPerCall() {
        assertEquals("Step 3", LanguageTranslator.translate("خطوة 3", "en"))
        assertEquals("Étape 3", LanguageTranslator.translate("خطوة 3", "fr"))
        assertEquals("Paso 3", LanguageTranslator.translate("خطوة 3", "es"))
    }

    @Test
    fun translatesPreviouslyUncoveredDownloadAndCommunityMessages() {
        val cases = listOf(
            "تم حظر تحويل إعلاني غير مرغوب؛ بقيت في الصفحة الأصلية" to listOf(
                "An unwanted advertising redirect was blocked; you stayed on the original page",
                "Une redirection publicitaire indésirable a été bloquée ; vous êtes resté sur la page d’origine",
                "Se bloqueó una redirección publicitaria no deseada; permaneciste en la página original"
            ),
            "تم حظر نافذة منبثقة غير مطلوبة؛ بقيت في الصفحة الأصلية" to listOf(
                "An unwanted pop-up was blocked; you stayed on the original page",
                "Une fenêtre contextuelle indésirable a été bloquée ; vous êtes resté sur la page d’origine",
                "Se bloqueó una ventana emergente no deseada; permaneciste en la página original"
            ),
            "الحجم المتبقي" to listOf("Remaining size", "Taille restante", "Tamaño restante"),
            "سبب إعادة المحاولة:" to listOf("Retry reason:", "Motif de la nouvelle tentative :", "Motivo del reintento:"),
            "جارٍ حجز مساحة التخزين قبل بدء التنزيل" to listOf(
                "Reserving storage space before starting the download",
                "Réservation de l’espace de stockage avant le début du téléchargement",
                "Reservando espacio de almacenamiento antes de iniciar la descarga"
            ),
            "فتح المجلد" to listOf("Open folder", "Ouvrir le dossier", "Abrir carpeta"),
            "مجتمع MARCO الرسمي على Telegram" to listOf(
                "Official MARCO community on Telegram",
                "Communauté officielle MARCO sur Telegram",
                "Comunidad oficial de MARCO en Telegram"
            ),
            "تابع أخبار وإعلانات MARCO وانضم إلى المجتمع" to listOf(
                "Follow MARCO news and announcements and join the community",
                "Suivez les actualités et annonces de MARCO et rejoignez la communauté",
                "Sigue las noticias y anuncios de MARCO y únete a la comunidad"
            ),
            "تم تحميل جزء من المكتبة. تحقق من الاتصال لإكمال التحديث." to listOf(
                "Part of the library loaded. Check your connection to finish updating.",
                "Une partie de la bibliothèque a été chargée. Vérifiez votre connexion pour terminer la mise à jour.",
                "Se cargó parte de la biblioteca. Comprueba tu conexión para terminar la actualización."
            )
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
        }
        assertEquals("Showing 8 of ", LanguageTranslator.translate("عرض 8 من ", "en"))
        assertEquals(
            "Visit the official MARCO RX6 website to see and download the latest release",
            LanguageTranslator.translate("افتح الموقع الرسمي لـ MARCO RX6 لمشاهدة أحدث إصدار وتحميله", "en")
        )
    }

    @Test
    fun publishingStudioStringsAreTranslated() {
        val cases = mapOf(
            "معرض صور اللعبة (اختياري)" to listOf("Game image gallery (optional)", "Galerie d’images du jeu (facultatif)", "Galería de imágenes del juego (opcional)"),
            "لم تتم إضافة صور إضافية بعد. يمكنك نشر اللعبة بالغلاف فقط." to listOf("No additional images have been added yet. You can publish the game with its cover only.", "Aucune image supplémentaire n’a encore été ajoutée. Vous pouvez publier le jeu avec sa seule couverture.", "Aún no se han añadido imágenes adicionales. Puedes publicar el juego solo con su portada."),
            "نقل الصورة للأعلى" to listOf("Move image up", "Déplacer l’image vers le haut", "Mover imagen hacia arriba"),
            "نقل الصورة للأسفل" to listOf("Move image down", "Déplacer l’image vers le bas", "Mover imagen hacia abajo"),
            "إزالة صورة المعرض" to listOf("Remove gallery image", "Supprimer l’image de la galerie", "Eliminar imagen de la galería"),
            "إضافة صور من الهاتف" to listOf("Add images from phone", "Ajouter des images depuis le téléphone", "Añadir imágenes desde el teléfono"),
            "يمكن إضافة 12 صورة كحد أقصى إلى المعرض" to listOf("You can add up to 12 images to the gallery", "Vous pouvez ajouter jusqu’à 12 images à la galerie", "Puedes añadir hasta 12 imágenes a la galería"),
            "جارٍ رفع صور المعرض إلى Google Drive..." to listOf("Uploading gallery images to Google Drive...", "Envoi des images de la galerie vers Google Drive...", "Subiendo imágenes de la galería a Google Drive..."),
            "تم رفع بعض الصور، وتعذر رفع الباقي: " to listOf("Some images were uploaded, but the remaining images could not be uploaded: ", "Certaines images ont été envoyées, mais les autres n’ont pas pu l’être : ", "Se subieron algunas imágenes, pero no se pudieron subir las demás: "),
            "تعذر رفع صور المعرض: " to listOf("Could not upload gallery images: ", "Impossible d’envoyer les images de la galerie : ", "No se pudieron subir las imágenes de la galería: "),
            "تم رفع " to listOf("Uploaded ", "Images envoyées : ", "Subidas "),
            " صورة وإضافتها إلى المعرض" to listOf(" image(s) and added them to the gallery", " image(s) ajoutée(s) à la galerie", " imagen(es) y añadida(s) a la galería"),
            "لم يتم رفع صور" to listOf("No images were uploaded", "Aucune image n’a été envoyée", "No se subieron imágenes"),
            "فيديو YouTube (اختياري)" to listOf("YouTube video (optional)", "Vidéo YouTube (facultatif)", "Vídeo de YouTube (opcional)"),
            "أدخل رابط YouTube صالحاً من youtube.com أو youtu.be" to listOf("Enter a valid YouTube link from youtube.com or youtu.be", "Saisissez un lien YouTube valide depuis youtube.com ou youtu.be", "Introduce un enlace válido de YouTube de youtube.com o youtu.be"),
            "رابط فيديو YouTube (اختياري)" to listOf("YouTube video URL (optional)", "Lien vidéo YouTube (facultatif)", "URL del vídeo de YouTube (opcional)")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }

    @Test
    fun translatesCommunityDirectoryIntoAllSupportedLanguages() {
        val cases = listOf(
            "قنوات المجتمع" to listOf("Community channels", "Chaînes de la communauté", "Canales de la comunidad"),
            "شكراً لأنك جزء من مجتمع MARCO" to listOf("Thank you for being part of the MARCO community", "Merci de faire partie de la communauté MARCO", "Gracias por formar parte de la comunidad MARCO"),
            "قنواتنا الرسمية" to listOf("Our official channels", "Nos chaînes officielles", "Nuestros canales oficiales"),
            "قناة أخبار ومحتوى PlayStation 3" to listOf("PlayStation 3 news and content channel", "Chaîne d’actualités et de contenu PlayStation 3", "Canal de noticias y contenido de PlayStation 3"),
            "قناة أخبار ومحتوى PlayStation 4" to listOf("PlayStation 4 news and content channel", "Chaîne d’actualités et de contenu PlayStation 4", "Canal de noticias y contenido de PlayStation 4"),
            "قناة أخبار ومحتوى PlayStation 5" to listOf("PlayStation 5 news and content channel", "Chaîne d’actualités et de contenu PlayStation 5", "Canal de noticias y contenido de PlayStation 5"),
            "مساحة مجتمع MARCO والنقاشات" to listOf("MARCO community and discussions", "Communauté MARCO et discussions", "Comunidad MARCO y debates"),
            "فيديوهات وشروحات MARCO" to listOf("MARCO videos and tutorials", "Vidéos et tutoriels MARCO", "Vídeos y tutoriales de MARCO")
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected[0], LanguageTranslator.translate(source, "en"))
            assertEquals(expected[1], LanguageTranslator.translate(source, "fr"))
            assertEquals(expected[2], LanguageTranslator.translate(source, "es"))
            assertEquals(source, LanguageTranslator.translate(source, "ar"))
        }
    }
}
