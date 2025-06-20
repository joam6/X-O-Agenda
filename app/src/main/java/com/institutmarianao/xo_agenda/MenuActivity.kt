package com.institutmarianao.xo_agenda

import android.Manifest
import android.app.Activity // Importar Activity
import android.content.Intent // Importar Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast // Importar Toast
import androidx.activity.result.ActivityResultLauncher // Importar ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts // Importar ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.gms.auth.api.signin.GoogleSignIn // Importar GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient // Importar GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions // Importar GoogleSignInOptions
import com.google.android.gms.common.api.ApiException // Importar ApiException
import com.google.android.gms.common.api.Scope // Importar Scope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount // Importar GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.institutmarianao.xo_agenda.alertas.NavigationIntentHandler
import com.institutmarianao.xo_agenda.alertas.PermisosHandler

import kotlinx.coroutines.Dispatchers // Si usas Coroutines (opcional, pero recomendado para tareas en segundo plano)
import kotlinx.coroutines.launch // Si usas Coroutines
import androidx.lifecycle.lifecycleScope // Si usas Coroutines (en Activity)
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport // Importar transporte HTTP
import com.google.api.client.json.gson.GsonFactory // Importar fábrica JSON
import com.google.api.services.calendar.Calendar // Importar el cliente de la API de Calendar
import com.google.api.services.calendar.CalendarScopes // Importar los scopes de Calendar
class MenuActivity : AppCompatActivity() {

    companion object {
        const val REQ_NOTIF = 1234
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvName: TextView
    private lateinit var tvEmail: TextView

    private lateinit var googleCalendarSignInClient: GoogleSignInClient
    private lateinit var googleCalendarSignInLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cargar el layout sin Toolbar
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        tvName = findViewById(R.id.tvUserName)
        tvEmail = findViewById(R.id.tvUserEmail)

        // Configura el cliente de Google Sign-In para la sincronización de Calendar
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestIdToken(getString(R.string.default_web_client_id)) // <-- Revisa si esta es la forma correcta de obtener tu web client ID
            .requestScopes(Scope("https://www.googleapis.com/auth/calendar.events")) // ¡Añade el SCOPE de Calendar!
            .build()

        googleCalendarSignInClient = GoogleSignIn.getClient(this, gso)

        // Inicializa el Activity Result Launcher para el inicio de sesión de Google Calendar
        googleCalendarSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data
                val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
                try {
                    val account = task.getResult(ApiException::class.java)
                    if (account != null) {
                        Log.d("MenuActivity", "GoogleSignInAccount received for Calendar sync.")
                        // startCalendarSyncWithGoogleAccount(account) // <-- ¡Esta es la función que vamos a añadir (placeholder por ahora)!

                    } else {
                        Log.w("MenuActivity", "GoogleSignInAccount is null after successful result for Calendar sync.")
                        Toast.makeText(this, "Error al obtener cuenta de Google para sincronizar.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: ApiException) {
                    Log.e("MenuActivity", "Google Sign-In failed for Calendar sync", e)
                    Toast.makeText(this, "Error al vincular con Google Calendar: ${e.statusCode}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.w("MenuActivity", "Google Sign-In for Calendar sync canceled or failed: resultCode = ${result.resultCode}")
                Toast.makeText(this, "Vinculación con Google Calendar cancelada.", Toast.LENGTH_SHORT).show()
            }
        }

        val navigateTo = intent.getStringExtra("navigateTo")
        if (savedInstanceState == null) {
            if (navigateTo == "alerts") {
                NavigationIntentHandler.handleNavigationIntent(this, intent)
            } else {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container_fragment, ProfileFragment())
                    .commit()
            }
        }

        val ivClose: ImageView = findViewById(R.id.ivClose)
        // Agrega un OnClickListener al botón de cerrar el menú
        ivClose.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        // Obtén el usuario actual de FirebaseAuth
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            tvEmail.text = user.email

            //  Creamos un listener en tiempo real sobre el documento del usuario en Firestore
            FirebaseFirestore.getInstance()
                .collection("usuarios")
                .document(user.uid)
                .addSnapshotListener { snapshot, error ->

                    // Si hubo un error, lo mostramos por consola
                    if (error != null) {
                        Log.w("FirestoreListener", "Error escuchando documento:", error)
                        return@addSnapshotListener
                    }

                    // Si el documento existe (es decir, el usuario tiene datos guardados)
                    if (snapshot != null && snapshot.exists()) {
                        //Obtenemos el campo "nom" del documento y lo ponemos en el TextView
                        val newName = snapshot.getString("nom")
                        tvName.text = newName ?: ""
                    }
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }

        // Define un mapa de View IDs a lambdas que devuelven el Fragment correspondiente
        val menuMap = mapOf(
            R.id.textPerfil to ::ProfileFragment,
            R.id.profileicon to ::ProfileFragment,
            R.id.textCalendario to ::CalendariFragment,
            R.id.calendaricon to ::CalendariFragment,
            R.id.textAlertas to ::AlertFragment,
            R.id.alertasicon to ::AlertFragment,
            R.id.settingsicon to ::SettingFragments
        )

        // En onCreate(), en lugar de cada listener por separado:
        menuMap.forEach { (viewId, fragmentCtor) ->
            findViewById<View>(viewId).setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container_fragment, fragmentCtor())
                    .addToBackStack(null)
                    .commit()
            }
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermisosHandler.handlePermissionsResult(this, requestCode, grantResults)
    }


    // Método público para abrir el menú desde cualquier fragmento
    fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    // (Opcional) cerrar drawer si está abierto
    fun closeDrawerIfOpen() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    // Método público para que el Fragment Settings dispare el flujo de sincronización
    fun startGoogleCalendarSyncFlow() {
       Log.d("MenuActivity", "Initiating Google Calendar sync flow...")

       // Primero, intenta obtener la última cuenta de Google con la que se autenticó
       val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)

       val calendarScope = Scope("https://www.googleapis.com/auth/calendar.events")
       val hasCalendarScope = lastSignedInAccount != null && lastSignedInAccount.grantedScopes.contains(calendarScope)


       if (hasCalendarScope) {
           Log.d("MenuActivity", "Google account already signed in with Calendar scope. Proceeding to sync.")
           lastSignedInAccount?.let { account ->
               startCalendarSyncWithGoogleAccount(account)
           }

       } else {
           Log.d("MenuActivity", "Google account not signed in or missing Calendar scope, initiating sign-in flow.")
           val signInIntent = googleCalendarSignInClient.signInIntent
           googleCalendarSignInLauncher.launch(signInIntent)
       }
   }

   private fun startCalendarSyncWithGoogleAccount(account: GoogleSignInAccount) {
       Log.d("MenuActivity", "startCalendarSyncWithGoogleAccount called for: ${account.email}")
       Toast.makeText(this, "Cuenta de Google vinculada. Preparando sincronización...", Toast.LENGTH_LONG).show()


       lifecycleScope.launch(Dispatchers.IO) {
           try {
               val credential = GoogleAccountCredential.usingOAuth2(
                   this@MenuActivity,
                   listOf(CalendarScopes.CALENDAR_EVENTS)
               )
               credential.selectedAccount = account.account

               // Construir el objeto Calendar (el cliente de la API)
               val calendarService = Calendar.Builder(
                   NetHttpTransport(),
                   GsonFactory(),
                   credential
               )
                   .setApplicationName("Calendari-ToDo List") // Establece el nombre de tu aplicación
                   .build()

               Log.d("MenuActivity", "Google Calendar service built successfully.")

               // Leer eventos del calendario principal
               try {
                   // Obtener la lista de calendarios del usuario
                   val calendars = calendarService.calendarList().list().execute()
                   Log.d("MenuActivity", "Fetched ${calendars.items.size} calendars.")

                   val primaryCalendar = calendars.items.firstOrNull { it.primary == true }
                   if (primaryCalendar != null) {
                       Log.d("MenuActivity", "Primary Calendar ID: ${primaryCalendar.id}")

                       runOnUiThread { // Vuelve al hilo principal para actualizar la UI
                           Toast.makeText(this@MenuActivity, "Sincronización iniciada (lógica pendiente)...", Toast.LENGTH_SHORT).show()
                       }

                   } else {
                       Log.w("MenuActivity", "Primary calendar not found for user.")
                       runOnUiThread { Toast.makeText(this@MenuActivity, "No se encontró un calendario principal de Google.", Toast.LENGTH_SHORT).show() }
                   }

               } catch (apiError: GoogleJsonResponseException) {
                   Log.e("MenuActivity", "Google API Error during calendar operation: ${apiError.statusCode}", apiError)
                   runOnUiThread { Toast.makeText(this@MenuActivity, "Error de API de Google (${apiError.statusCode}): ${apiError.details?.message ?: apiError.statusMessage}", Toast.LENGTH_LONG).show() }
               } catch (e: Exception) {
                   Log.e("MenuActivity", "Error during Google Calendar API interaction", e)
                   runOnUiThread { Toast.makeText(this@MenuActivity, "Error general de sincronización: ${e.message}", Toast.LENGTH_LONG).show() }
               }
           } catch (e: Exception) {
               Log.e("MenuActivity", "Error building Google Account Credential or Calendar service", e)
               runOnUiThread {
                   Toast.makeText(this@MenuActivity, "Error interno al preparar sincronización.", Toast.LENGTH_SHORT).show()
               }
           }
       }
   }
}