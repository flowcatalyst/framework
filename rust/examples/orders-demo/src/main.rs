//! `cargo run -p orders-demo -- demo` (from `rust/examples/orders-demo`, with PostgreSQL as in
//! `config/application.yaml`): places an order, adds two lines and cancels it, printing each
//! committed event; without a command it starts and runs until SIGTERM or SIGINT.

use std::env;
use std::io::Write;
use std::process::ExitCode;

use fw_core::{
    AppBuilder, AppName, Boot, BoxFuture, ClientId, Command, Context, PrincipalId, launch,
};
use orders_demo::usecases::{AddLine, CancelOrder, PlaceOrder, Policies};
use orders_demo::{OrdersApp, build, declare};

/// `demo`: one order through its life.
struct Demo(OrdersApp);

impl Command for Demo {
    fn name(&self) -> &'static str {
        "demo"
    }

    fn run<'a>(
        &'a self,
        _args: Vec<String>,
        out: &'a mut (dyn Write + Send),
    ) -> BoxFuture<'a, ExitCode> {
        Box::pin(async move {
            let (Ok(client), Ok(principal)) =
                (ClientId::new("demo-client"), PrincipalId::new("demo-user"))
            else {
                return ExitCode::FAILURE;
            };
            let ctx = Context::new(client).with_principal(principal);
            let app = &self.0;
            let result = async {
                let placed = app
                    .use_cases
                    .run(&app.orders.place_order, &ctx, PlaceOrder::parse("Acme")?)
                    .await?
                    .into_inner();
                let order = placed.order_id.clone();
                let line = app
                    .use_cases
                    .run(
                        &app.orders.add_line,
                        &ctx,
                        AddLine::parse(order.clone(), "BOLT-1", 3, 125)?,
                    )
                    .await?;
                let cancelled = app
                    .use_cases
                    .run(
                        &app.orders.cancel_order,
                        &ctx,
                        CancelOrder::parse(order, "changed mind")?,
                    )
                    .await?;
                Ok::<_, fw_usecase::UseCaseError>((
                    placed,
                    line.into_inner(),
                    cancelled.into_inner(),
                ))
            }
            .await;
            match result {
                Ok((placed, line, cancelled)) => {
                    let lines = [
                        format!(
                            "placed {} for {}",
                            placed.order_id,
                            placed.customer.as_str()
                        ),
                        format!(
                            "added {} ({} x {} cents), {} line(s)",
                            line.line_id,
                            line.quantity.get(),
                            line.unit_price_cents.get(),
                            line.line_count
                        ),
                        format!("cancelled {}: {}", cancelled.order_id, cancelled.reason),
                    ];
                    for l in lines {
                        if writeln!(out, "{l}").is_err() {
                            return ExitCode::FAILURE;
                        }
                    }
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    writeln!(out, "failed: {e}").ok();
                    ExitCode::FAILURE
                }
            }
        })
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new("info"))
        .init();
    let mut app = AppBuilder::new(AppName::of("orders-demo"));
    let declared = declare(&mut app);
    launch(app, env::args(), async |boot: &mut Boot| {
        let built = build(boot, &declared, Policies::default()).await?;
        boot.command(Demo(built));
        Ok(())
    })
    .await
}
