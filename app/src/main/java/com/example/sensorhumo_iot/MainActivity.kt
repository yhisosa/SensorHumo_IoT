package com.example.sensorhumo_iot

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

    // --- REFERENCIAS DE LÓGICA ---
    private lateinit var auth: FirebaseAuth
    private lateinit var varDatabase: DatabaseReference // La hacemos var para poder reasignarla
    private lateinit var tcpManager: ComunicacionTCP

    // --- ESTADO DE LA UI ---
    private val estadoConexion = mutableStateOf("Estado: Desconectado")
    private val lecturaHumo = mutableStateOf("---")
    private val usuarioLogueado = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. INICIALIZACIÓN DE LÓGICA
        auth = Firebase.auth
        // Inicialización temporal de la base de datos (se reasigna al loguear)
        varDatabase = FirebaseDatabase.getInstance().reference.child("temp")
        tcpManager = ComunicacionTCP(this)

        // Comprobar si el usuario ya inició sesión (Persistencia)
        if (auth.currentUser != null) {
            usuarioLogueado.value = true
            // Apuntamos la base de datos al nodo del usuario (Paso 6)
            varDatabase = FirebaseDatabase.getInstance().reference.child("datosUsuarios").child(auth.currentUser!!.uid)
        }

        // 2. RENDERIZADO DE LA UI (COMPOSE)
        setContent {
            SensorHumo_IoTTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    // --- CONTROLADOR DE NAVEGACIÓN ---
                    if (usuarioLogueado.value) {
                        PantallaPrincipal(
                            modifier = Modifier.padding(innerPadding),
                            estadoConexionActual = estadoConexion.value,
                            lecturaHumoActual = lecturaHumo.value,
                            onConectarClick = { ipPuerto ->
                                val (ip, puerto) = parsearDireccion(ipPuerto)
                                if (ip != null && puerto != null) {
                                    estadoConexion.value = "Estado: Intentando conectar..."
                                    tcpManager.conectar(ip, puerto)
                                } else {
                                    estadoConexion.value = "Error: Formato IP:Puerto incorrecto."
                                }
                            },
                            onActivarClick = {
                                tcpManager.enviarComando("RELAY_ON")
                                guardarEnFirebase(RegistroHumo(
                                    lecturaHumo = lecturaHumo.value.toIntOrNull() ?: 0,
                                    evento = "CONTROL_RELAY_ON"
                                ))
                            },
                            onSilenciarClick = {
                                tcpManager.enviarComando("ALARM_OFF")
                                guardarEnFirebase(RegistroHumo(
                                    lecturaHumo = lecturaHumo.value.toIntOrNull() ?: 0,
                                    evento = "CONTROL_ALARM_OFF"
                                ))
                            },
                            // Lógica de cerrar sesión (solicitado)
                            onCerrarSesionClick = {
                                auth.signOut()
                                usuarioLogueado.value = false
                            }
                        )
                    } else {
                        // Muestra la pantalla de Login/Registro
                        PantallaLogin(
                            modifier = Modifier.padding(innerPadding),
                            onLoginClick = { email, password ->
                                iniciarSesion(email, password)
                            },
                            onRegisterClick = { email, password ->
                                registrarUsuario(email, password)
                            }
                        )
                    }
                }
            }
        }
    }

    // --- FUNCIONES DE AUTENTICACIÓN (Paso 6) ---
    private fun registrarUsuario(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) {
            Toast.makeText(baseContext, "Correo y contraseña no pueden estar vacíos.", Toast.LENGTH_SHORT).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = task.result?.user
                    if (user != null) {
                        varDatabase = FirebaseDatabase.getInstance().reference.child("datosUsuarios").child(user.uid)
                        usuarioLogueado.value = true
                    } else {
                        Toast.makeText(baseContext, "Fallo el registro: No se pudo obtener el usuario.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(baseContext, "Fallo el registro: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun iniciarSesion(email: String, pass: String) {
        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        varDatabase = FirebaseDatabase.getInstance().reference.child("datosUsuarios").child(user.uid)
                        usuarioLogueado.value = true
                    }
                } else {
                    Toast.makeText(baseContext, "Fallo el inicio de sesión.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // --- FUNCIONES DE CALLBACK ---

    fun actualizarEstadoConexion(estado: String) {
        estadoConexion.value = estado
    }

    fun actualizarUIyGuardar(datoReal: Int) {
        lecturaHumo.value = datoReal.toString()

        val evento = if (datoReal > 1000) "ALARMA_HUMO_ONLINE" else "LECTURA_NORMAL"
        guardarEnFirebase(RegistroHumo(
            lecturaHumo = datoReal,
            evento = evento
        ))
    }

    fun mostrarAlertaHumo() {
        Toast.makeText(this, "🚨 ¡ALARMA DE HUMO DETECTADA! 🚨", Toast.LENGTH_LONG).show()
    }

    fun activarModoOffline() {
        // Rutina llamada al perder la conexión (Paso 5)
        println("MODO OFFLINE ACTIVADO: Rutina de sincronización pendiente...")
        // Aquí iría la lógica de SharedPreferences
    }

    // --- FUNCIONES INTERNAS ---
    fun guardarEnFirebase(registro: RegistroHumo) {
        // Guarda los datos DENTRO del nodo del usuario (Paso 5/Paso 6)
        varDatabase.push().setValue(registro)
            .addOnSuccessListener {
                println("Registro subido a Firebase: ${registro.lecturaHumo}")
            }
            .addOnFailureListener {
                println("Error al subir a Firebase: ${it.message}")
                // Aquí iría la rutina para guardar en SharedPreferences
            }
    }

    private fun parsearDireccion(ipPuerto: String): Pair<String?, Int?> {
        return try {
            val partes = ipPuerto.split(":")
            if (partes.size == 2) {
                Pair(partes[0], partes[1].toInt())
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            Pair(null, null)
        }
    }
}


// --- PANTALLA DE LOGIN (NUEVA) ---
@Composable
fun PantallaLogin(
    modifier: Modifier = Modifier,
    onLoginClick: (String, String) -> Unit,
    onRegisterClick: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Sensor de Humo IoT - Acceso",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Correo Electrónico") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onLoginClick(email, password) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Iniciar Sesión")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { onRegisterClick(email, password) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Registrarse")
        }
    }
}


// --- PANTALLA PRINCIPAL (Composable UI) ---
@Composable
fun PantallaPrincipal(
    modifier: Modifier = Modifier,
    estadoConexionActual: String,
    lecturaHumoActual: String,
    onConectarClick: (String) -> Unit,
    onActivarClick: () -> Unit,
    onSilenciarClick: () -> Unit,
    onCerrarSesionClick: () -> Unit
) {
    var ipPuerto by remember { mutableStateOf("192.168.1.15:8080") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "TI3042 - Sensor de Humo IoT",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = ipPuerto,
            onValueChange = { ipPuerto = it },
            label = { Text("IP:Puerto del ESP32") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onConectarClick(ipPuerto) },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Conectar")
        }

        Text(
            text = estadoConexionActual,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Lectura del Sensor (Descifrado):",
            fontSize = 18.sp
        )

        Text(
            text = lecturaHumoActual,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onActivarClick() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Activar Extractor (Relé ON)")
        }

        Button(
            onClick = { onSilenciarClick() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Silenciar Alarma (LED OFF)")
        }

        Spacer(modifier = Modifier.weight(1f)) // Empuja el botón de cerrar sesión hacia abajo

        Button(
            onClick = { onCerrarSesionClick() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) // Color rojo
        ) {
            Text("Cerrar Sesión")
        }
    }
}