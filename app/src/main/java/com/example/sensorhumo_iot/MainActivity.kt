package com.example.sensorhumo_iot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sensorhumo_iot.ui.theme.SensorHumo_IoTTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.ktx.Firebase

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var varDatabase: DatabaseReference
    private lateinit var tcpManager: ComunicacionTCP

    // --- ESTADO DE LA UI ---
    private val estadoConexion = mutableStateOf("Desconectado 🔴")
    private val lecturaHumo = mutableStateOf("---")
    private val usuarioLogueado = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        auth = Firebase.auth
        varDatabase = FirebaseDatabase.getInstance().reference.child("temp")
        tcpManager = ComunicacionTCP(this)

        if (auth.currentUser != null) {
            usuarioLogueado.value = true
            varDatabase = FirebaseDatabase.getInstance().reference.child("datosUsuarios").child(auth.currentUser!!.uid)
            sincronizarDatosLocales() // Intenta subir datos al iniciar (Paso 5)
        }

        setContent {
            SensorHumo_IoTTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    if (usuarioLogueado.value) {
                        PantallaPrincipal(
                            estadoConexion = estadoConexion.value,
                            lecturaHumo = lecturaHumo.value,
                            onConectar = { ip ->
                                val (addr, port) = parsearDireccion(ip)
                                if (addr != null && port != null) {
                                    estadoConexion.value = "Conectando... 🟡"
                                    tcpManager.conectar(addr, port)
                                } else {
                                    mostrarAlerta("IP Incorrecta. Ej: 192.168.1.15:8080")
                                }
                            },
                            onAccion = { cmd, log ->
                                tcpManager.enviarComando(cmd)
                                val valorHumo = lecturaHumo.value.toIntOrNull() ?: 0
                                guardarDatos(RegistroHumo(lecturaHumo = valorHumo, evento = log))
                            },
                            onSalir = {
                                auth.signOut()
                                usuarioLogueado.value = false
                            },
                            onSincronizar = { sincronizarDatosLocales() } // Botón Sincronizar
                        )
                    } else {
                        PantallaLogin(
                            onLogin = { e, p -> iniciarSesion(e, p) },
                            onRegistro = { e, p -> registrarUsuario(e, p) }
                        )
                    }
                }
            }
        }
    }

    // --- LÓGICA DE ALMACENAMIENTO OFFLINE (PASO 5) ---

    private fun guardarDatos(registro: RegistroHumo) {
        // Verifica la conexión antes de intentar subir
        if (!hayInternet()) {
            guardarLocalmente(registro)
            return
        }
        guardarEnFirebase(registro)
    }

    private fun guardarEnFirebase(registro: RegistroHumo) {
        // Intenta subir a Firebase
        varDatabase.push().setValue(registro)
            .addOnSuccessListener {
                println("Firebase: Dato subido correctamente")
            }
            .addOnFailureListener {
                // Si Firebase falla por un problema temporal o de red, guarda local
                guardarLocalmente(registro)
            }
    }

    private fun guardarLocalmente(registro: RegistroHumo) {
        // Usamos SharedPreferences para guardar datos temporalmente
        val sharedPref = getSharedPreferences(auth.currentUser?.uid ?: "TEMP_GUEST", Context.MODE_PRIVATE)
        val editor = sharedPref.edit()

        // Creamos un string simple para guardar: "valor|evento|timestamp"
        val datoString = "${registro.lecturaHumo}|${registro.evento}|${registro.timestamp}"

        // Usamos el timestamp como llave única
        editor.putString(registro.timestamp.toString(), datoString)
        editor.apply()

        mostrarAlerta("⚠️ Sin Internet: Dato guardado en el teléfono")
    }

    private fun sincronizarDatosLocales() {
        val sharedPref = getSharedPreferences(auth.currentUser?.uid ?: "TEMP_GUEST", Context.MODE_PRIVATE)
        val todosLosDatos = sharedPref.all

        if (todosLosDatos.isEmpty()) {
            mostrarAlerta("✅ Todo sincronizado. No hay datos pendientes.")
            return
        }

        if (!hayInternet()) {
            mostrarAlerta("❌ No hay internet para sincronizar. Conéctate y vuelve a intentar.")
            return
        }

        mostrarAlerta("🔄 Sincronizando ${todosLosDatos.size} datos pendientes...")

        for ((key, value) in todosLosDatos) {
            val datos = (value as String).split("|")
            if (datos.size == 3) {
                val registroRecuperado = RegistroHumo(
                    lecturaHumo = datos[0].toInt(),
                    evento = datos[1],
                    timestamp = datos[2].toLong(),
                    estado = "OFFLINE_SYNC"
                )

                // Subir a Firebase
                varDatabase.push().setValue(registroRecuperado).addOnSuccessListener {
                    // Si sube bien, borramos del teléfono
                    val editor = sharedPref.edit()
                    editor.remove(key)
                    editor.apply()
                }
            }
        }
    }

    private fun hayInternet(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        // Verifica que la red tenga capacidades de internet
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- REGISTRO Y LOGIN ---
    private fun registrarUsuario(email: String, pass: String) {
        if (email.isBlank() || pass.length < 6) { mostrarAlerta("Error: Datos inválidos"); return }
        auth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val user = task.result?.user
                if (user != null) {
                    varDatabase = FirebaseDatabase.getInstance().reference.child("datosUsuarios").child(user.uid)
                    usuarioLogueado.value = true
                    sincronizarDatosLocales()
                }
            } else mostrarAlerta("Fallo: ${task.exception?.message}")
        }
    }

    private fun iniciarSesion(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) return
        auth.signInWithEmailAndPassword(email, pass).addOnCompleteListener(this) { task ->
            if (task.isSuccessful && auth.currentUser != null) {
                varDatabase = FirebaseDatabase.getInstance().reference.child("datosUsuarios").child(auth.currentUser!!.uid)
                usuarioLogueado.value = true
                sincronizarDatosLocales()
            } else mostrarAlerta("Error al iniciar sesión")
        }
    }

    // --- CALLBACKS Y UTILIDADES ---
    fun actualizarEstadoConexion(estado: String) { estadoConexion.value = estado }

    fun actualizarUIyGuardar(dato: Int) {
        lecturaHumo.value = dato.toString()
        val evento = if (dato > 2000) "PELIGRO_HUMO" else "NORMAL"
        guardarDatos(RegistroHumo(lecturaHumo = dato, evento = evento))
    }

    fun mostrarAlerta(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    fun activarModoOffline() { println("Conexión con ESP32 perdida.") }

    private fun parsearDireccion(ip: String): Pair<String?, Int?> {
        return try {
            val p = ip.split(":")
            if (p.size == 2) Pair(p[0], p[1].toInt()) else Pair(null, null)
        } catch (e: Exception) { Pair(null, null) }
    }
}

// --- PANTALLAS COMPOSABLES ---
@Composable
fun PantallaLogin(onLogin: (String, String) -> Unit, onRegistro: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔐", fontSize = 50.sp, color = Color.White)
        Text("IoT Seguro", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(30.dp))

        CampoTexto(email, "Correo") { email = it }
        Spacer(Modifier.height(10.dp))
        CampoTexto(pass, "Contraseña", true) { pass = it }

        Spacer(Modifier.height(25.dp))
        BotonBonito("Iniciar Sesión", Color(0xFF2196F3)) { onLogin(email, pass) }
        Spacer(Modifier.height(10.dp))
        BotonBonito("Registrarse", Color(0xFF4CAF50)) { onRegistro(email, pass) }
    }
}

@Composable
fun PantallaPrincipal(
    estadoConexion: String,
    lecturaHumo: String,
    onConectar: (String) -> Unit,
    onAccion: (String, String) -> Unit,
    onSalir: () -> Unit,
    onSincronizar: () -> Unit
) {
    var ip by remember { mutableStateOf("172.20.10.3:8080") }
    val nivelHumo = lecturaHumo.toIntOrNull() ?: 0
    val colorEstado = when {
        nivelHumo > 2000 -> Color(0xFFCF6679)
        nivelHumo > 1000 -> Color(0xFFFFEB3B)
        else -> Color(0xFF03DAC5)
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {

        // Tarjeta Conexión
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("📡 Control Panel", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text(estadoConexion, color = Color.Gray, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                CampoTexto(ip, "IP del ESP32") { ip = it }
                Spacer(Modifier.height(10.dp))
                BotonBonito("Conectar", Color(0xFF6200EE)) { onConectar(ip) }
            }
        }
        Spacer(Modifier.height(15.dp))

        // Tarjeta Sensor
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nivel de Humo", color = Color.White)
                Text(text = lecturaHumo, fontSize = 50.sp, fontWeight = FontWeight.Bold, color = colorEstado)
                Text(if (nivelHumo > 2000) "🔥 PELIGRO 🔥" else "✅ Normal", color = colorEstado)
            }
        }
        Spacer(Modifier.height(20.dp))

        // Botones
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BotonAccion("💨\nVentilación", Color(0xFF03A9F4)) { onAccion("RELAY_ON", "SISTEMA_ON") }
            BotonAccion("🔕\nApagar", Color(0xFFFF5722)) { onAccion("ALARM_OFF", "SISTEMA_OFF") }
        }
        Spacer(Modifier.height(20.dp))

        // Botón de Sincronización Manual (Paso 5)
        BotonBonito("🔄 Sincronizar Datos", Color(0xFF4CAF50)) { onSincronizar() }

        Spacer(Modifier.weight(1f))
        BotonBonito("Cerrar Sesión", Color(0xFFB00020)) { onSalir() }
    }
}

// --- COMPONENTES ---
@Composable
fun CampoTexto(valor: String, label: String, esPass: Boolean = false, onCambio: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        label = { Text(label, color = Color.Gray) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFFBB86FC),
            unfocusedBorderColor = Color.Gray
        ),
        visualTransformation = if (esPass) PasswordVisualTransformation() else VisualTransformation.None,
        // *** ARREGLO FINAL: Usamos la ruta completa para evitar conflictos de imports ***
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (esPass) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun BotonBonito(texto: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Text(texto, fontSize = 16.sp, color = Color.White)
    }
}

@Composable
fun BotonAccion(texto: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(130.dp, 80.dp)) {
        Text(texto, textAlign = TextAlign.Center, fontSize = 14.sp, color = Color.Black)
    }
}