package com.example.nenekmode;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wizard setup 4 langkah untuk pemakaian pertama (is_first_time):
 * 1) nama lansia & asisten  2) nomor darurat & kontak favorit
 * 3) jadwal minum obat      4) ringkasan & selesai.
 * Semua langkah bisa dilewati; hasil langsung disimpan ke prefs.
 */
public class SetupWizardActivity extends AppCompatActivity {

    private TextView wizTitle, wizSubtitle, wizSummary;
    private LinearLayout step1, step2, step3, step4;
    private EditText wizNenekName, wizAssistantName;
    private EditText wizEmergencyPhone, wizContactName, wizContactPhone;
    private Switch wizMedSwitch;
    private EditText wizMed1, wizMed2, wizMed3;
    private Button wizBack, wizNext;

    private SharedPreferences prefs;
    private int step = 0;

    private static final String[] TITLES = {
            "Selamat Datang! 👋", "Nomor Darurat 🚨", "Jadwal Minum Obat 💊", "Semua Siap! ✅"
    };
    private static final String[] SUBTITLES = {
            "Isi nama panggilan dan nama asistennya ya.",
            "Nomor yang dihubungi saat darurat. Bisa dilewati dulu.",
            "Kapan waktu minum obat? Contoh: 08:00. Bisa dilewati dulu.",
            "Pengaturan awal sudah lengkap. Bisa diubah kapan saja di Pengaturan."
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        prefs = getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);

        wizTitle = findViewById(R.id.wizTitle);
        wizSubtitle = findViewById(R.id.wizSubtitle);
        wizSummary = findViewById(R.id.wizSummary);
        step1 = findViewById(R.id.wizStep1);
        step2 = findViewById(R.id.wizStep2);
        step3 = findViewById(R.id.wizStep3);
        step4 = findViewById(R.id.wizStep4);
        wizNenekName = findViewById(R.id.wizNenekName);
        wizAssistantName = findViewById(R.id.wizAssistantName);
        wizEmergencyPhone = findViewById(R.id.wizEmergencyPhone);
        wizContactName = findViewById(R.id.wizContactName);
        wizContactPhone = findViewById(R.id.wizContactPhone);
        wizMedSwitch = findViewById(R.id.wizMedSwitch);
        wizMed1 = findViewById(R.id.wizMed1);
        wizMed2 = findViewById(R.id.wizMed2);
        wizMed3 = findViewById(R.id.wizMed3);
        wizBack = findViewById(R.id.wizBack);
        wizNext = findViewById(R.id.wizNext);

        // prefill kalau sudah pernah diisi
        wizNenekName.setText(prefs.getString("nenek_name", "Bu"));
        wizAssistantName.setText(prefs.getString("assistant_name", "Kandar"));
        wizEmergencyPhone.setText(prefs.getString("emergency_number", ""));
        wizContactName.setText(prefs.getString("favorite_contact_name", ""));
        wizContactPhone.setText(prefs.getString("favorite_contact_number", ""));

        wizBack.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (step > 0) { step--; showStep(); }
        });
        wizNext.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            onNext();
        });

        showStep();
        SpeechUtil.speak(this, "Selamat datang! Ayo kita kenalan dulu. Isi nama panggilan dan nama asisten ya.");
    }

    private void showStep() {
        wizTitle.setText(TITLES[step]);
        wizSubtitle.setText(SUBTITLES[step]);
        step1.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        step2.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        step3.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        step4.setVisibility(step == 3 ? View.VISIBLE : View.GONE);
        wizBack.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        wizNext.setText(step == 3 ? "Mulai Ngobrol 🎤" : "Lanjut");
        if (step == 3) fillSummary();
    }

    private void onNext() {
        if (step < 3) {
            step++;
            showStep();
            return;
        }
        saveAndFinish();
    }

    private void fillSummary() {
        String nenek = wizNenekName.getText().toString().trim();
        String asisten = wizAssistantName.getText().toString().trim();
        String darurat = wizEmergencyPhone.getText().toString().trim();
        boolean medOn = wizMedSwitch.isChecked();

        StringBuilder sb = new StringBuilder();
        sb.append("• Asisten: ").append(asisten.isEmpty() ? "Kandar" : asisten)
          .append(" menemani ").append(nenek.isEmpty() ? "Bu" : nenek).append("\n");
        sb.append("• Nomor darurat: ").append(darurat.isEmpty() ? "(belum diisi)" : darurat).append("\n");
        if (medOn) {
            StringBuilder times = new StringBuilder();
            String[] slots = {wizMed1.getText().toString().trim(), wizMed2.getText().toString().trim(),
                    wizMed3.getText().toString().trim()};
            for (String s : slots) {
                if (s.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")) {
                    if (times.length() > 0) times.append(", ");
                    times.append(s);
                }
            }
            sb.append("• Pengingat obat: ").append(times.length() > 0 ? times : "aktif (jadwal default)");
        } else {
            sb.append("• Pengingat obat: nonaktif");
        }
        wizSummary.setText(sb.toString());
    }

    private void saveAndFinish() {
        SharedPreferences.Editor e = prefs.edit();
        String nenek = wizNenekName.getText().toString().trim();
        String asisten = wizAssistantName.getText().toString().trim();

        e.putString("nenek_name", nenek.isEmpty() ? "Bu" : nenek);
        e.putString("assistant_name", asisten.isEmpty() ? "Kandar" : asisten);
        e.putString("emergency_number", wizEmergencyPhone.getText().toString().trim());
        e.putString("favorite_contact_name", wizContactName.getText().toString().trim());
        e.putString("favorite_contact_number", wizContactPhone.getText().toString().trim());

        boolean medOn = wizMedSwitch.isChecked();
        e.putBoolean("reminder_enabled", medOn);
        try {
            JSONArray arr = new JSONArray();
            String[] slots = {wizMed1.getText().toString().trim(), wizMed2.getText().toString().trim(),
                    wizMed3.getText().toString().trim()};
            for (String s : slots) {
                if (s.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")) {
                    String[] p = s.split(":");
                    arr.put(new JSONObject().put("h", Integer.parseInt(p[0]))
                            .put("m", Integer.parseInt(p[1])).put("en", true));
                }
            }
            if (medOn && arr.length() > 0) {
                e.putString("med_times", arr.toString());
            }
        } catch (Exception ignored) {}
        e.putBoolean("is_first_time", false);
        e.apply();

        if (medOn) {
            ReminderReceiver.setReminders(this);
        }

        Toast.makeText(this, "Setup selesai! Selamat mencoba ya.", Toast.LENGTH_LONG).show();
        SpeechUtil.speak(this, "Semua siap! " + (asisten.isEmpty() ? "Aku" : asisten)
                + " siap nemenin " + (nenek.isEmpty() ? "Bu" : nenek)
                + " ngobrol. Silakan bicara kapan saja ya.");
        finish();
    }
}
