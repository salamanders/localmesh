package info.benjaminhill.localmesh.visualizationtester

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val visualizations = assets.list("web")?.toList() ?: emptyList()
        setContent {
            VisualizationList(visualizations = visualizations)
        }
    }
}

@Composable
fun VisualizationList(visualizations: List<String>) {
    val context = LocalContext.current
    LazyColumn {
        items(visualizations) { visualization ->
            Text(
                text = visualization,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(context, VisualizationActivity::class.java).apply {
                            putExtra("visualization", visualization)
                        }
                        context.startActivity(intent)
                    }
                    .padding(16.dp)
            )
        }
    }
}
